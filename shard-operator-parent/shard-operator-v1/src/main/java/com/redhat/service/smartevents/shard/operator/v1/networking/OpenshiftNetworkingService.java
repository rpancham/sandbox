package com.redhat.service.smartevents.shard.operator.v1.networking;

import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.service.smartevents.shard.operator.core.utils.EventSourceFactory;
import com.redhat.service.smartevents.shard.operator.v1.providers.GlobalConfigurationsConstants;
import com.redhat.service.smartevents.shard.operator.v1.providers.IstioGatewayProvider;
import com.redhat.service.smartevents.shard.operator.v1.providers.TemplateImportConfig;
import com.redhat.service.smartevents.shard.operator.v1.providers.TemplateProvider;
import com.redhat.service.smartevents.shard.operator.v1.resources.BridgeIngress;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RoutePortBuilder;
import io.fabric8.openshift.api.model.RouteTargetReferenceBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;

import static com.redhat.service.smartevents.shard.operator.v1.networking.OpenshiftRouteSpecMatchesHelper.matches;

public class OpenshiftNetworkingService implements NetworkingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkingService.class);
    public static final String CLUSTER_DOMAIN_RESOURCE_NAME = "cluster";

    private final OpenShiftClient client;
    private final TemplateProvider templateProvider;
    private final IstioGatewayProvider istioGatewayProvider;

    public OpenshiftNetworkingService(OpenShiftClient client, TemplateProvider templateProvider, IstioGatewayProvider istioGatewayProvider) {
        this.client = client;
        this.templateProvider = templateProvider;
        this.istioGatewayProvider = istioGatewayProvider;
    }

    @Override
    public EventSource buildInformerEventSource(String component) {
        return EventSourceFactory.buildRoutesInformer(client, component);
    }

    @Override
    // TODO: refactor as we don't need anymore NetworkResource
    public NetworkResource fetchOrCreateBrokerNetworkIngress(BridgeIngress bridgeIngress, Secret secret, String path) {
        Service service = istioGatewayProvider.getIstioGatewayService();
        Route expected = buildRoute(bridgeIngress, secret, service);

        Route existing = client.routes()
                .inNamespace(service.getMetadata().getNamespace())
                .withName(bridgeIngress.getMetadata().getName())
                .get();

        if (existing == null || !matches(existing.getSpec(), expected.getSpec())) {
            client.routes()
                    .inNamespace(service.getMetadata().getNamespace())
                    .withName(bridgeIngress.getMetadata().getName())
                    .createOrReplace(expected);
            return buildNetworkingResource(expected, path);
        }
        return buildNetworkingResource(existing, path);
    }

    @Override
    public boolean delete(String name, String namespace) {
        try {
            return client.routes().inNamespace(namespace).withName(name).delete();
        } catch (Exception e) {
            LOGGER.debug("Can't delete ingress with name '{}' because it does not exist", name);
            return false;
        }
    }

    private Route buildRoute(BridgeIngress bridgeIngress, Secret secret, Service service) {
        /**
         * As the service might not be in the same namespace of the bridgeIngress (for example for the istio gateway) we can not set the owner references.
         */
        Route route = templateProvider.loadBridgeIngressOpenshiftRouteTemplate(bridgeIngress,
                new TemplateImportConfig()
                        .withNameFromParent()
                        .withPrimaryResourceFromParent());
        // Inherit namespace from service and not from bridgeIngress
        route.getMetadata().setNamespace(service.getMetadata().getNamespace());

        // We have to provide the host manually in order not to exceed the 63 char limit in the dns label https://issues.redhat.com/browse/MGDOBR-271
        route.getSpec().setHost(bridgeIngress.getSpec().getHost());

        route.getSpec().getTls()
                .setCertificate(new String(Base64.getDecoder().decode(secret.getData().get(GlobalConfigurationsConstants.TLS_CERTIFICATE_SECRET))));
        route.getSpec().getTls()
                .setKey(new String(Base64.getDecoder().decode(secret.getData().get(GlobalConfigurationsConstants.TLS_KEY_SECRET))));

        route.getSpec().setTo(new RouteTargetReferenceBuilder()
                .withKind("Service")
                .withName(service.getMetadata().getName())
                .build());
        route.getSpec().setPort(new RoutePortBuilder().withTargetPort(new IntOrString("http2")).build());
        return route;
    }

    private NetworkResource buildNetworkingResource(Route route, String path) {
        if (route.getStatus() != null && "Admitted".equals(route.getStatus().getIngress().get(0).getConditions().get(0).getType())) {
            return new NetworkResource(null, true);
        }

        LOGGER.info("Route '{}' not ready yet", route.getMetadata().getName());
        return new NetworkResource(null, false);
    }
}
