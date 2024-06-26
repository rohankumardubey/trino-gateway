/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.baseapp;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MoreCollectors;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import io.airlift.log.Logger;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthFilter;
import io.dropwizard.core.Application;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.server.DefaultServerFactory;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.trino.gateway.ha.log.GatewayRequestLogFactory;
import io.trino.gateway.ha.module.RouterBaseModule;
import io.trino.gateway.ha.module.StochasticRoutingManagerProvider;
import io.trino.gateway.ha.resource.EntityEditorResource;
import io.trino.gateway.ha.resource.GatewayResource;
import io.trino.gateway.ha.resource.GatewayViewResource;
import io.trino.gateway.ha.resource.GatewayWebAppResource;
import io.trino.gateway.ha.resource.HaGatewayResource;
import io.trino.gateway.ha.resource.LoginResource;
import io.trino.gateway.ha.resource.PublicResource;
import io.trino.gateway.ha.resource.TrinoResource;
import io.trino.gateway.ha.security.AuthorizedExceptionMapper;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Supports Guice in Dropwizard.
 *
 * <p>To use it, create a subclass and provide a list of modules you want to use with the {@link
 * #addModules} method.
 *
 * <p>Packages supplied in the constructor will be scanned for Resources, Providers, and Managed
 * classes, and added to the environment.
 *
 * <p>GuiceApplication also makes {@link com.codahale.metrics.MetricRegistry} available for
 * injection.
 */
public abstract class BaseApp<T extends AppConfiguration>
        extends Application<T>
{
    private static final Logger logger = Logger.get(BaseApp.class);
    private final ImmutableList.Builder<Module> appModules = ImmutableList.builder();

    private AppModule newModule(String clazz, T configuration, Environment environment)
    {
        try {
            logger.info("Trying to load module [%s]", clazz);
            Object module =
                    Class.forName(clazz)
                            .getConstructor(configuration.getClass(), Environment.class)
                            .newInstance(configuration, environment);
            return ((AppModule) module);
        }
        catch (Exception e) {
            logger.error(e, "Could not instantiate module [%s]", clazz);
            onFatalError(e);
        }
        return null;
    }

    private void validateModules(List<AppModule> modules, T configuration, Environment environment)
    {
        Optional<AppModule> routerProvider = modules.stream()
                .filter(module -> module instanceof RouterBaseModule)
                .collect(MoreCollectors.toOptional());
        if (routerProvider.isEmpty()) {
            logger.warn("Router provider doesn't exist in the config, using the StochasticRoutingManagerProvider");
            String clazz = StochasticRoutingManagerProvider.class.getCanonicalName();
            modules.add(newModule(clazz, configuration, environment));
        }
    }

    @Override // Using Airlift logger
    protected void bootstrapLogging() {}

    /**
     * When the application runs, this is called after the bundles are run.
     *
     * @param configuration the parsed {@link Configuration} object
     * @param environment the application's {@link Environment}
     * @throws Exception if something goes wrong
     */
    @Override
    public void run(T configuration, Environment environment)
            throws Exception
    {
        ((DefaultServerFactory) configuration.getServerFactory()).setRequestLogFactory(new GatewayRequestLogFactory());
        configureGuice(configuration, environment);
    }

    private void configureGuice(T configuration, Environment environment)
    {
        appModules.add(new MetricRegistryModule(environment.metrics()));
        appModules.addAll(addModules(configuration, environment));
        Injector injector = Guice.createInjector(appModules.build());
        injector.injectMembers(this);
        registerWithInjector(configuration, environment, injector);
    }

    private void registerWithInjector(T configuration, Environment environment, Injector injector)
    {
        logger.info("op=register_start configuration=%s", configuration.toString());
        registerAuthFilters(environment, injector);
        registerProviders(environment, injector);
        addManagedApps(configuration, environment, injector);
        registerResources(environment, injector);
        logger.info("op=register_end configuration=%s", configuration.toString());
    }

    /**
     * Supply a list of modules to be used by Guice.
     *
     * @param configuration the app configuration
     * @return a list of modules to be provisioned by Guice
     */
    protected List<AppModule> addModules(T configuration, Environment environment)
    {
        List<AppModule> modules = new ArrayList<>();
        if (configuration.getModules() == null) {
            logger.warn("No modules to load.");
            return modules;
        }
        for (String clazz : configuration.getModules()) {
            modules.add(newModule(clazz, configuration, environment));
        }

        validateModules(modules, configuration, environment);

        return modules;
    }

    /**
     * Supply a list of managed apps.
     */
    protected List<Managed> addManagedApps(
            T configuration, Environment environment, Injector injector)
    {
        List<Managed> managedApps = new ArrayList<>();
        if (configuration.getManagedApps() == null) {
            logger.error("No managed apps found");
            return managedApps;
        }
        configuration
                .getManagedApps()
                .forEach(
                        clazz -> {
                            try {
                                Class c = Class.forName(clazz);
                                LifecycleEnvironment lifecycle = environment.lifecycle();
                                lifecycle.manage((Managed) injector.getInstance(c));
                                logger.info("op=register type=managed item=%s", c);
                            }
                            catch (Exception e) {
                                logger.error(e, "Error loading managed app");
                            }
                        });
        return managedApps;
    }

    private void registerProviders(Environment environment, Injector injector)
    {
        final Set<Class<?>> classes = ImmutableSet.of(AuthorizedExceptionMapper.class);
        classes.forEach(
                c -> {
                    environment.jersey().register(injector.getInstance(c));
                    logger.info("op=register type=provider item=%s", c);
                });
    }

    private void registerResources(Environment environment, Injector injector)
    {
        final Set<Class<?>> classes = ImmutableSet.of(
                EntityEditorResource.class,
                GatewayResource.class,
                GatewayViewResource.class,
                GatewayWebAppResource.class,
                HaGatewayResource.class,
                LoginResource.class,
                PublicResource.class,
                TrinoResource.class);
        classes.forEach(
                c -> {
                    environment.jersey().register(injector.getInstance(c));
                    logger.info("op=register type=resource item=%s", c);
                });
    }

    private void registerAuthFilters(Environment environment, Injector injector)
    {
        environment
                .jersey()
                .register(new AuthDynamicFeature(injector.getInstance(AuthFilter.class)));
        logger.info("op=register type=auth filter item=%s", AuthFilter.class);
        environment.jersey().register(RolesAllowedDynamicFeature.class);
    }
}
