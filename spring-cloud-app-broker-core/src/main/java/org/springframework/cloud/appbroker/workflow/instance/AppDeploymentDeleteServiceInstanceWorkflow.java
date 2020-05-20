/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.appbroker.workflow.instance;

import java.util.Collections;
import java.util.Map;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;

import org.springframework.cloud.appbroker.deployer.BackingAppDeploymentService;
import org.springframework.cloud.appbroker.deployer.BackingService;
import org.springframework.cloud.appbroker.deployer.BackingServicesProvisionService;
import org.springframework.cloud.appbroker.deployer.BrokeredServices;
import org.springframework.cloud.appbroker.deployer.DeploymentProperties;
import org.springframework.cloud.appbroker.extensions.credentials.CredentialProviderService;
import org.springframework.cloud.appbroker.extensions.targets.TargetService;
import org.springframework.cloud.appbroker.manager.BackingAppManagementService;
import org.springframework.cloud.appbroker.service.DeleteServiceInstanceWorkflow;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceResponse.DeleteServiceInstanceResponseBuilder;
import org.springframework.core.annotation.Order;
import org.springframework.util.CollectionUtils;

@Order(0)
public class AppDeploymentDeleteServiceInstanceWorkflow
	extends AppDeploymentInstanceWorkflow
	implements DeleteServiceInstanceWorkflow {

	private final Logger log = Loggers.getLogger(AppDeploymentDeleteServiceInstanceWorkflow.class);

	private final BackingAppDeploymentService deploymentService;

	private final BackingAppManagementService backingAppManagementService;

	private final CredentialProviderService credentialProviderService;

	private final TargetService targetService;

	private final BackingServicesProvisionService backingServicesProvisionService;

	public AppDeploymentDeleteServiceInstanceWorkflow(BrokeredServices brokeredServices,
		BackingAppDeploymentService deploymentService,
		BackingAppManagementService backingAppManagementService,
		BackingServicesProvisionService backingServicesProvisionService,
		CredentialProviderService credentialProviderService,
		TargetService targetService) {
		super(brokeredServices);
		this.deploymentService = deploymentService;
		this.backingAppManagementService = backingAppManagementService;
		this.credentialProviderService = credentialProviderService;
		this.targetService = targetService;
		this.backingServicesProvisionService = backingServicesProvisionService;
	}

	@Override
	public Mono<Void> delete(DeleteServiceInstanceRequest request, DeleteServiceInstanceResponse response) {
		return deleteBackingServices(request)
			.thenMany(undeployBackingApplications(request))
			.then();
	}

	private Flux<String> deleteBackingServices(DeleteServiceInstanceRequest request) {
		return collectBackingServices(request)
			.collectList()
			.flatMapMany(backingServices -> {
				if (!CollectionUtils.isEmpty(backingServices)) {
					return backingServicesProvisionService.deleteServiceInstance(backingServices);
				}
				return Flux.empty();
			})
			.doOnComplete(() -> log.debug("Finished deleting backing services for {}/{}",
				request.getServiceDefinition().getName(), request.getPlan().getName()))
			.doOnError(exception -> log.error(String.format("Error deleting backing services for %s/%s with error '%s'",
				request.getServiceDefinition().getName(), request.getPlan().getName(), exception.getMessage()),
				exception));
	}

	private Flux<BackingService> collectBackingServices(DeleteServiceInstanceRequest request) {
		return collectConfiguredBackingServices(request)
			.concatWith(collectBoundBackingServices(request))
			.distinct(BackingService::serviceInstanceNameAndSpaceHashCode);
	}

	private Flux<BackingService> collectConfiguredBackingServices(DeleteServiceInstanceRequest request) {
		return getBackingServicesForService(request.getServiceDefinition(), request.getPlan())
			.flatMap(backingServices -> getTargetForService(request.getServiceDefinition(), request.getPlan())
				.flatMap(targetSpec -> targetService.addToBackingServices(backingServices, targetSpec,
					request.getServiceInstanceId()))
				.defaultIfEmpty(backingServices))
			.flatMapMany(Flux::fromIterable);
	}

	private Flux<BackingService> collectBoundBackingServices(DeleteServiceInstanceRequest request) {
		return backingAppManagementService.getDeployedBackingApplications(request.getServiceInstanceId(),
			request.getServiceDefinition().getName(), request.getPlan().getName())
			.flatMapMany(Flux::fromIterable)
			.flatMap(backingApplication -> Mono.justOrEmpty(backingApplication.getServices())
				.flatMapMany(Flux::fromIterable)
				.flatMap(servicesSpec -> Mono.justOrEmpty(servicesSpec.getServiceInstanceName()))
				.map(serviceInstanceName -> {
					Map<String, String> properties = null;
					if (!CollectionUtils.isEmpty(backingApplication.getProperties())) {
						String target = backingApplication.getProperties()
							.get(DeploymentProperties.TARGET_PROPERTY_KEY);
						properties = Collections.singletonMap(DeploymentProperties.TARGET_PROPERTY_KEY, target);
					}
					return BackingService.builder()
						.serviceInstanceName(serviceInstanceName)
						.properties(properties)
						.build();
				}));
	}

	private Flux<String> undeployBackingApplications(DeleteServiceInstanceRequest request) {
		return getBackingApplicationsForService(request.getServiceDefinition(), request.getPlan())
			.flatMap(backingApps ->
				credentialProviderService.deleteCredentials(backingApps,
					request.getServiceInstanceId()))
			.flatMap(backingApps -> getTargetForService(request.getServiceDefinition(), request.getPlan())
				.flatMap(targetSpec -> targetService.addToBackingApplications(backingApps, targetSpec,
					request.getServiceInstanceId()))
				.defaultIfEmpty(backingApps))
			.flatMapMany(deploymentService::undeploy)
			.doOnRequest(l -> log.debug("Undeploying backing applications for {}/{}",
				request.getServiceDefinition().getName(), request.getPlan().getName()))
			.doOnComplete(() -> log.debug("Finished undeploying backing applications for {}/{}",
				request.getServiceDefinition().getName(), request.getPlan().getName()))
			.doOnError(
				exception -> log.error(String.format("Error undeploying backing applications for %s/%s with error '%s'",
					request.getServiceDefinition().getName(), request.getPlan().getName(), exception.getMessage()),
					exception));
	}

	@Override
	public Mono<Boolean> accept(DeleteServiceInstanceRequest request) {
		return accept(request.getServiceDefinition(), request.getPlan());
	}

	@Override
	public Mono<DeleteServiceInstanceResponseBuilder> buildResponse(DeleteServiceInstanceRequest request,
		DeleteServiceInstanceResponseBuilder responseBuilder) {
		return Mono.just(responseBuilder.async(true));
	}

}
