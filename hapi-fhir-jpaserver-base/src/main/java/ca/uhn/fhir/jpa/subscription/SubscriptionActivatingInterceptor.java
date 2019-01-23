package ca.uhn.fhir.jpa.subscription;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.jpa.config.BaseConfig;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.DaoRegistry;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.model.search.QueryParser;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.subscription.module.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.module.ResourceModifiedMessage;
import ca.uhn.fhir.jpa.subscription.module.cache.SubscriptionCanonicalizer;
import ca.uhn.fhir.jpa.subscription.module.cache.SubscriptionRegistry;
import ca.uhn.fhir.model.dstu2.valueset.ResourceTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.interceptor.ServerOperationInterceptorAdapter;
import ca.uhn.fhir.util.SubscriptionUtil;
import com.google.common.annotations.VisibleForTesting;
import org.hl7.fhir.instance.model.Subscription;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for transitioning subscription resources from REQUESTED to ACTIVE
 * Once activated, the subscription is added to the SubscriptionRegistry.
 *
 * Also validates criteria.  If invalid, rejects the subscription without persisting the subscription.
 */
@Service
@Lazy
public class SubscriptionActivatingInterceptor extends ServerOperationInterceptorAdapter {
	private Logger ourLog = LoggerFactory.getLogger(SubscriptionActivatingInterceptor.class);

	private static boolean ourWaitForSubscriptionActivationSynchronouslyForUnitTest;
	private static final String REQUESTED_STATUS = Subscription.SubscriptionStatus.REQUESTED.toCode();
	private static final String ACTIVE_STATUS = Subscription.SubscriptionStatus.ACTIVE.toCode();

	@Autowired
	private PlatformTransactionManager myTransactionManager;
	@Autowired
	@Qualifier(BaseConfig.TASK_EXECUTOR_NAME)
	private AsyncTaskExecutor myTaskExecutor;
	@Autowired
	private SubscriptionRegistry mySubscriptionRegistry;
	@Autowired
	private DaoRegistry myDaoRegistry;
	@Autowired
	private FhirContext myFhirContext;
	@Autowired
	private SubscriptionCanonicalizer mySubscriptionCanonicalizer;
	@Autowired
	private MatchUrlService myMatchUrlService;
	@Autowired
	private DaoConfig myDaoConfig;

	public boolean activateOrRegisterSubscriptionIfRequired(final IBaseResource theSubscription) {
		// Grab the value for "Subscription.channel.type" so we can see if this
		// subscriber applies..
		String subscriptionChannelTypeCode = myFhirContext
			.newTerser()
			.getSingleValueOrNull(theSubscription, SubscriptionMatcherInterceptor.SUBSCRIPTION_TYPE, IPrimitiveType.class)
			.getValueAsString();

		Subscription.SubscriptionChannelType subscriptionChannelType = Subscription.SubscriptionChannelType.fromCode(subscriptionChannelTypeCode);
		// Only activate supported subscriptions
		if (!myDaoConfig.getSupportedSubscriptionTypes().contains(subscriptionChannelType)) {
			return false;
		}

		final IPrimitiveType<?> status = myFhirContext.newTerser().getSingleValueOrNull(theSubscription, SubscriptionMatcherInterceptor.SUBSCRIPTION_STATUS, IPrimitiveType.class);
		String statusString = status.getValueAsString();

		if (REQUESTED_STATUS.equals(statusString)) {
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				/*
				 * If we're in a transaction, we don't want to try and change the status from
				 * requested to active within the same transaction because it's too late by
				 * the time we get here to make modifications to the payload.
				 *
				 * So, we register a synchronization, meaning that when the transaction is
				 * finished, we'll schedule a task to do this in a separate worker thread
				 * to avoid any possibility of conflict.
				 */
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
					@Override
					public void afterCommit() {
						Future<?> activationFuture = myTaskExecutor.submit(new Runnable() {
							@Override
							public void run() {
								activateSubscription(ACTIVE_STATUS, theSubscription, REQUESTED_STATUS);
							}
						});

						/*
						 * If we're running in a unit test, it's nice to be predictable in
						 * terms of order... In the real world it's a recipe for deadlocks
						 */
						if (ourWaitForSubscriptionActivationSynchronouslyForUnitTest) {
							try {
								activationFuture.get(5, TimeUnit.SECONDS);
							} catch (Exception e) {
								ourLog.error("Failed to activate subscription", e);
							}
						}
					}
				});
				return true;
			} else {
				return activateSubscription(ACTIVE_STATUS, theSubscription, REQUESTED_STATUS);
			}
		} else if (ACTIVE_STATUS.equals(statusString)) {
			return mySubscriptionRegistry.registerSubscriptionUnlessAlreadyRegistered(theSubscription);
		} else {
			// Status isn't "active" or "requested"
			return mySubscriptionRegistry.unregisterSubscriptionIfRegistered(theSubscription, statusString);
		}
	}


	private boolean activateSubscription(String theActiveStatus, final IBaseResource theSubscription, String theRequestedStatus) {
		IFhirResourceDao subscriptionDao = myDaoRegistry.getSubscriptionDao();
		IBaseResource subscription = subscriptionDao.read(theSubscription.getIdElement());

		ourLog.info("Activating subscription {} from status {} to {}", subscription.getIdElement().toUnqualified().getValue(), theRequestedStatus, theActiveStatus);
		try {
			SubscriptionUtil.setStatus(myFhirContext, subscription, theActiveStatus);
			subscription = subscriptionDao.update(subscription).getResource();
			submitResourceModifiedForUpdate(subscription);
			return true;
		} catch (final UnprocessableEntityException e) {
			ourLog.info("Changing status of {} to ERROR", subscription.getIdElement());
			SubscriptionUtil.setStatus(myFhirContext, subscription, "error");
			SubscriptionUtil.setReason(myFhirContext, subscription, e.getMessage());
			subscriptionDao.update(subscription);
			return false;
		}
	}

	void submitResourceModifiedForUpdate(IBaseResource theNewResource) {
		submitResourceModified(theNewResource, ResourceModifiedMessage.OperationTypeEnum.UPDATE);
	}

	@Override
	public void resourceCreated(RequestDetails theRequest, IBaseResource theResource) {
		submitResourceModified(theResource, ResourceModifiedMessage.OperationTypeEnum.CREATE);
	}

	@Override
	public void resourceDeleted(RequestDetails theRequest, IBaseResource theResource) {
		submitResourceModified(theResource, ResourceModifiedMessage.OperationTypeEnum.DELETE);
	}

	@Override
	public void resourceUpdated(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {
		submitResourceModified(theNewResource, ResourceModifiedMessage.OperationTypeEnum.UPDATE);
	}

	private void submitResourceModified(IBaseResource theNewResource, ResourceModifiedMessage.OperationTypeEnum theOperationType) {
		submitResourceModified(new ResourceModifiedMessage(myFhirContext, theNewResource, theOperationType));
	}

	private void submitResourceModified(final ResourceModifiedMessage theMsg) {
		IIdType id = theMsg.getId(myFhirContext);
		if (!id.getResourceType().equals(ResourceTypeEnum.SUBSCRIPTION.getCode())) {
			return;
		}
		switch (theMsg.getOperationType()) {
			case DELETE:
				mySubscriptionRegistry.unregisterSubscription(id);
				break;
			case CREATE:
			case UPDATE:
				final IBaseResource subscription = theMsg.getNewPayload(myFhirContext);
				validateCriteria(subscription);
				activateAndRegisterSubscriptionIfRequiredInTransaction(subscription);
				break;
			default:
				break;
		}
	}

	public void validateCriteria(final IBaseResource theResource) {
		CanonicalSubscription subscription = mySubscriptionCanonicalizer.canonicalize(theResource);
		String criteria = subscription.getCriteriaString();
		try {
			RuntimeResourceDefinition resourceDef = QueryParser.parseUrlResourceType(myFhirContext, criteria);
			myMatchUrlService.translateMatchUrl(criteria, resourceDef);
		} catch (InvalidRequestException e) {
			throw new UnprocessableEntityException("Invalid subscription criteria submitted: " + criteria + " " + e.getMessage());
		}
	}

	private void activateAndRegisterSubscriptionIfRequiredInTransaction(IBaseResource theSubscription) {
		TransactionTemplate txTemplate = new TransactionTemplate(myTransactionManager);
		txTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				activateOrRegisterSubscriptionIfRequired(theSubscription);
			}
		});
	}


	@VisibleForTesting
	public static void setWaitForSubscriptionActivationSynchronouslyForUnitTest(boolean theWaitForSubscriptionActivationSynchronouslyForUnitTest) {
		ourWaitForSubscriptionActivationSynchronouslyForUnitTest = theWaitForSubscriptionActivationSynchronouslyForUnitTest;
	}

}
