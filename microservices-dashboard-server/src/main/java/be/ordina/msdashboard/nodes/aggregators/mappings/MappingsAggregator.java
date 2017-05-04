/*
 * Copyright 2012-2017 the original author or authors.
 *
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
 */
package be.ordina.msdashboard.nodes.aggregators.mappings;

import be.ordina.msdashboard.nodes.aggregators.ErrorHandler;
import be.ordina.msdashboard.nodes.aggregators.NettyServiceCaller;
import be.ordina.msdashboard.nodes.aggregators.NodeAggregator;
import be.ordina.msdashboard.nodes.model.Node;
import be.ordina.msdashboard.nodes.uriresolvers.UriResolver;
import be.ordina.msdashboard.security.strategies.SecurityProtocolStrategy;
import be.ordina.msdashboard.security.strategies.StrategyFactory;
import be.ordina.msdashboard.security.strategy.SecurityProtocol;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import rx.Observable;
import rx.schedulers.Schedulers;

import java.util.List;
import java.util.Map;

import static be.ordina.msdashboard.nodes.aggregators.Constants.ZUUL;

/**
 * Aggregates nodes from mappings information exposed by Spring Boot's Actuator.
 * <p>
 * In case Spring Boot is not used for a microservice, your service must comply to
 * the mappings format exposed by Spring Boot under the <code>/mappings</code> endpoint.
 * Example of a mappings response:
 * <p>
 * <pre>
 * {
 *   "{[/endpoint1],methods=[GET],produces=[application/json]}" : {
 *     "bean" : "requestMappingHandlerMapping",
 *     "method" : "public java.util.Date be.ordina.controllers.Endpoint1Controller.retrieveDate(java.util.Date)"
 *   },
 *   "{[/endpoint2],methods=[GET, POST]}" : {
 *     "bean" : "requestMappingHandlerMapping",
 *     "method" : "public void be.ordina.controllers.Endpoint2Controller.createInformation(be.ordina.model.Information)"
 *   }
 * }
 * </pre>
 * <p>
 * Any endpoint with a method signature containing <code>org.springframework</code> will be ignored.
 *
 * @author Andreas Evers
 * @author Kevin van Houtte
 * @see <a href="http://docs.spring.io/spring-boot/docs/current-SNAPSHOT/reference/htmlsingle/#production-ready">
 * Spring Boot Actuator</a>
 */
public class MappingsAggregator implements NodeAggregator {

	private static final Logger logger = LoggerFactory.getLogger(MappingsAggregator.class);

	private DiscoveryClient discoveryClient;
	private UriResolver uriResolver;
	private MappingsProperties properties;
	private NettyServiceCaller caller;
	private ErrorHandler errorHandler;
	private StrategyFactory strategyFactory;

	public MappingsAggregator(final DiscoveryClient discoveryClient, final UriResolver uriResolver,
							  final MappingsProperties properties, final NettyServiceCaller caller,
							  final ErrorHandler errorHandler, final StrategyFactory strategyFactory) {
		this.discoveryClient = discoveryClient;
		this.uriResolver = uriResolver;
		this.properties = properties;
		this.caller = caller;
		this.errorHandler = errorHandler;
		this.strategyFactory = strategyFactory;
	}

	@Override
	public Observable<Node> aggregateNodes() {
		final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		Observable<Observable<Node>> observableObservable = getServiceIdsFromDiscoveryClient()
				.map(id -> new ImmutablePair<>(id, resolveMappingsUrl(id)))
				.doOnNext(pair -> logger.info("Creating mappings observable: " + pair))
				.map(pair -> getMappingNodesFromService(pair.getLeft(), pair.getRight(), auth))
				.doOnNext(el -> logger.debug("Unmerged mappings observable: " + el))
				.doOnError(e -> errorHandler.handleSystemError("Error filtering services: " + e.getMessage(), e))
				.doOnCompleted(() -> logger.info("Completed getting all mappings observables"))
				.retry();
		return Observable.merge(observableObservable)
				.doOnNext(el -> logger.debug("Merged health node: " + el.getId()))
				.doOnError(e -> errorHandler.handleSystemError("Error filtering services: " + e.getMessage(), e))
				.doOnCompleted(() -> logger.info("Completed merging all mappings observables"));
	}

	private String resolveMappingsUrl(String id) {
		List<ServiceInstance> instances = discoveryClient.getInstances(id);
		if (instances.isEmpty()) {
			throw new IllegalStateException("No instances found for service " + id);
		} else {
			return uriResolver.resolveMappingsUrl(instances.get(0));
		}
	}

	protected Observable<String> getServiceIdsFromDiscoveryClient() {
		logger.info("Discovering services for mappings");
		return Observable.from(discoveryClient.getServices()).subscribeOn(Schedulers.io()).publish().autoConnect()
				.map(id -> id.toLowerCase())
				.filter(id -> !id.equals(ZUUL))
				.doOnNext(s -> logger.debug("Service discovered: " + s))
				.doOnError(e -> errorHandler.handleSystemError("Error filtering services: " + e.getMessage(), e))
				.retry();
	}

	protected Observable<Node> getMappingNodesFromService(String serviceId, String url, final Authentication authentication) {
		HttpClientRequest<ByteBuf> request = HttpClientRequest.createGet(url);
		SecurityContextHolder.getContext().setAuthentication(authentication);
		SecurityProtocol securityProtocol = SecurityProtocol.valueOf(properties.getSecurity().toUpperCase());
		strategyFactory.getStrategy(SecurityProtocolStrategy.class, securityProtocol).apply(request);
		for (Map.Entry<String, String> header : properties.getRequestHeaders().entrySet()) {
			request.withHeader(header.getKey(), header.getValue());
		}
		return caller.retrieveJsonFromRequest(serviceId, request)
				.map(source -> MappingsToNodeConverter.convertToNodes(serviceId, source))
				.flatMap(el -> el)
				.filter(node -> !properties.getFilteredServices().contains(node.getId()))
				.doOnNext(el -> logger.info("Mapping node {} discovered in url: {}", el.getId(), url))
				.doOnError(e -> logger.error("Error during mapping node fetching: ", e))
				.doOnCompleted(() -> logger.info("Completed emission of a mapping node observable from url: " + url))
				.onErrorResumeNext(Observable.empty());
	}
}
