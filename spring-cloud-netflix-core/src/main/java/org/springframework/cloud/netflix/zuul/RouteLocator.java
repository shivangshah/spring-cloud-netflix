package org.springframework.cloud.netflix.zuul;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.bind.PropertySourceUtils;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.*;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Spencer Gibb
 */
@Slf4j
public class RouteLocator implements ApplicationListener<EnvironmentChangeEvent> {

    public static final String DEFAULT_ROUTE = "/";

    @Autowired
    protected ConfigurableEnvironment env;

    @Autowired
    protected DiscoveryClient discovery;

    @Autowired
    protected ZuulProperties properties;

    private Field propertySourcesField;
    private AtomicReference<LinkedHashMap<String, String>> routes = new AtomicReference<>();

    public RouteLocator() {
        initField();
    }

    private void initField() {
        propertySourcesField = ReflectionUtils.findField(CompositePropertySource.class, "propertySources");
        propertySourcesField.setAccessible(true);
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        for (String key : event.getKeys()) {
            if (key.startsWith(properties.getRoutePrefix())) {
                routes.set(locateRoutes());
                return;
            }
        }
    }

    //TODO: respond to changes in eureka
    public Map<String, String> getRoutes() {
        if (routes.get() == null) {
            routes.set(locateRoutes());
        }

        return routes.get();
    }

    protected LinkedHashMap<String, String> locateRoutes() {
        LinkedHashMap<String, String> routesMap = new LinkedHashMap<>();

        //Add routes for discovery services by default
        List<String> services = discovery.getServices();
        for (String serviceId : services) {
            //Ignore specified services
            if (!properties.getIgnoredServices().contains(serviceId))
                routesMap.put("/" + serviceId + "/**", serviceId);
        }

        MutablePropertySources propertySources = env.getPropertySources();
        for (PropertySource<?> propertySource : propertySources) {
            getRoutes(propertySource, routesMap);
        }

        String defaultServiceId = routesMap.get(DEFAULT_ROUTE);

        if (defaultServiceId != null) {
            //move the defaultServiceId to the end
            routesMap.remove(DEFAULT_ROUTE);
            routesMap.put(DEFAULT_ROUTE, defaultServiceId);
        }
        return routesMap;
    }

    protected void getRoutes(PropertySource<?> propertySource, Map<String, String> routes) {
        if (propertySource instanceof CompositePropertySource) {
            try {
                @SuppressWarnings("unchecked")
                Set<PropertySource<?>> sources = (Set<PropertySource<?>>) propertySourcesField.get(propertySource);
                for (PropertySource<?> source : sources) {
                    getRoutes(source, routes);
                }
            } catch (IllegalAccessException e) {
                return;
            }
        } else {
            //EnumerablePropertySource enumerable = (EnumerablePropertySource) propertySource;
            MutablePropertySources propertySources = new MutablePropertySources();
            propertySources.addLast(propertySource);
            Map<String, Object> routeEntries = PropertySourceUtils.getSubProperties(propertySources, properties.getRoutePrefix());
            for (Map.Entry<String, Object> entry : routeEntries.entrySet()) {
                String serviceId = entry.getKey();
                String route = entry.getValue().toString();

                if (routes.containsKey(route)) {
                    log.warn("Overwriting route {}: already defined by {}", route, routes.get(route));
                }
                routes.put(route, serviceId);
            }
        }
    }
}