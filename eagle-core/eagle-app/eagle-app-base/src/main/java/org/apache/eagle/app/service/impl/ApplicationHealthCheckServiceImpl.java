/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eagle.app.service.impl;

import com.codahale.metrics.health.HealthCheck;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.dropwizard.setup.Environment;
import org.apache.eagle.app.service.ApplicationHealthCheckPublisher;
import org.apache.eagle.app.service.ApplicationHealthCheckService;
import org.apache.eagle.app.service.ApplicationProviderService;
import org.apache.eagle.app.spi.ApplicationProvider;
import org.apache.eagle.metadata.model.ApplicationEntity;
import org.apache.eagle.metadata.service.ApplicationEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ApplicationHealthCheckServiceImpl extends ApplicationHealthCheckService {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationHealthCheckServiceImpl.class);

    private final ApplicationProviderService applicationProviderService;
    private final ApplicationEntityService applicationEntityService;
    private ApplicationHealthCheckPublisher applicationHealthCheckPublisher;
    private final Config config;
    private Environment environment;
    private Map<String, Optional<HealthCheck>> appHealthChecks = new HashMap<>();
    private final Object lock = new Object();
    private int initialDelay = 10;
    private int period = 300;

    private static final String HEALTH_INITIAL_DELAY_PATH = "application.healthCheck.initialDelay";
    private static final String HEALTH_PERIOD_PATH = "application.healthCheck.period";
    private static final String HEALTH_PUBLISHER_PATH = "application.healthCheck.publisher";
    private static final String HEALTH_PUBLISHER_IMPL_PATH = "application.healthCheck.publisher.publisherImpl";
    private static final String HEALTH_CHECK_DAILY_SEND_HOUR_PATH = "application.healthCheck.publisher.dailySendHour";
    private static final String SERVICE_PATH = "service";
    private static final String TIMEZONE_PATH = "service.timezone";
    private static final String HEALTHY = "OK";
    private static final String DAILY_TYPE = "[DAILY] - ";
    private static final String ERROR_TYPE = "[ERROR] - ";

    private boolean hasSendDaily = false;

    private TimeZone timeZone;

    @Inject
    private Injector currentInjector;

    @Inject
    public ApplicationHealthCheckServiceImpl(ApplicationProviderService applicationProviderService,
                                             ApplicationEntityService applicationEntityService,
                                             Config config) {
        this.applicationProviderService = applicationProviderService;
        this.applicationEntityService = applicationEntityService;
        this.config = config;
        if (this.config.hasPath(HEALTH_INITIAL_DELAY_PATH)) {
            this.initialDelay = this.config.getInt(HEALTH_INITIAL_DELAY_PATH);
        }

        if (this.config.hasPath(HEALTH_PERIOD_PATH)) {
            this.period = this.config.getInt(HEALTH_PERIOD_PATH);
        }

        this.applicationHealthCheckPublisher = null;
        if (this.config.hasPath(HEALTH_PUBLISHER_PATH)) {
            try {
                String className = this.config.getString(HEALTH_PUBLISHER_IMPL_PATH);
                Class<?> clz;
                clz = Thread.currentThread().getContextClassLoader().loadClass(className);
                if (ApplicationHealthCheckPublisher.class.isAssignableFrom(clz)) {
                    Constructor<?> cotr = clz.getConstructor(Config.class);
                    this.applicationHealthCheckPublisher = (ApplicationHealthCheckPublisher)cotr.newInstance(
                            this.config.getConfig(HEALTH_PUBLISHER_PATH).withFallback(this.config.getConfig(SERVICE_PATH)));
                }
            } catch (Exception e) {
                LOG.warn("Exception found when create ApplicationHealthCheckPublisher instance {}", e.getCause());
            }
        }
    }

    @Override
    public void init(Environment environment) {
        this.environment = environment;
        registerAll();
        this.timeZone = TimeZone.getTimeZone(config.getString(TIMEZONE_PATH));
    }

    private void registerAll() {
        Collection<ApplicationEntity> applicationEntities = applicationEntityService.findAll();
        applicationEntities.forEach(this::register);
    }

    @Override
    public void register(ApplicationEntity appEntity) {
        if (environment == null) {
            LOG.warn("Environment is null, can not register");
            return;
        }
        ApplicationProvider<?> appProvider = applicationProviderService.getApplicationProviderByType(appEntity.getDescriptor().getType());
        Optional<HealthCheck> applicationHealthCheck = appProvider.getManagedHealthCheck(
                        ConfigFactory.parseMap(appEntity.getContext())
                        .withFallback(config)
                        .withFallback(ConfigFactory.parseMap(appEntity.getConfiguration()))
        );

        if (!applicationHealthCheck.isPresent()) {
            LOG.warn("Application {} does not implement HealthCheck", appEntity.getAppId());
            return;
        }
        this.environment.healthChecks().register(appEntity.getAppId(), applicationHealthCheck.get());
        currentInjector.injectMembers(applicationHealthCheck.get());
        synchronized (lock) {
            if (!appHealthChecks.containsKey(appEntity.getAppId())) {
                appHealthChecks.put(appEntity.getAppId(), applicationHealthCheck);
                LOG.info("Successfully register health check for {}", appEntity.getAppId());
            }
        }
    }

    @Override
    public void unregister(ApplicationEntity appEntity) {
        if (environment == null) {
            LOG.warn("Environment is null, can not unregister");
            return;
        }
        this.environment.healthChecks().unregister(appEntity.getAppId());
        synchronized (lock) {
            appHealthChecks.remove(appEntity.getAppId());
        }
        LOG.info("Successfully unregister health check for {}", appEntity.getAppId());
    }

    @Override
    protected void runOneIteration() throws Exception {
        LOG.info("Starting ApplicationHealthCheckService");
        registerAll();

        boolean isDaily = false;
        int dailySendHour = config.getInt(HEALTH_CHECK_DAILY_SEND_HOUR_PATH);

        GregorianCalendar cal = new GregorianCalendar(timeZone);
        if (cal.get(Calendar.HOUR_OF_DAY) == dailySendHour && !hasSendDaily) {
            isDaily = true;
        }

        if (cal.get(Calendar.HOUR_OF_DAY) != dailySendHour) {
            hasSendDaily = false;
        }
        Map<String, HealthCheck> copyAppHealthChecks = new HashMap<>();
        synchronized (lock) {
            for (String appId : appHealthChecks.keySet()) {
                copyAppHealthChecks.put(appId, appHealthChecks.get(appId).get());
            }
        }

        Map<String, HealthCheck.Result> results = new HashMap<>();
        for (String appId : copyAppHealthChecks.keySet()) {
            HealthCheck.Result result = copyAppHealthChecks.get(appId).execute();
            if (result.isHealthy()) {
                if (isDaily) {
                    if (result.getMessage() == null || result.getMessage().isEmpty()) {
                        results.put(appId, HealthCheck.Result.healthy(HEALTHY));
                    } else {
                        results.put(appId, result);
                    }
                }
                LOG.info("Application {} is healthy", appId);
            } else {
                results.put(appId, result);
                LOG.warn("Application {} is not healthy, {}", appId, result.getMessage(), result.getError());
            }
        }

        String type = ERROR_TYPE;
        if (this.applicationHealthCheckPublisher != null) {
            try {
                if (isDaily) {
                    type = DAILY_TYPE;
                }
                this.applicationHealthCheckPublisher.onUnHealthApplication(type, results);
                if (isDaily) {
                    hasSendDaily = true;
                }
            } catch (Exception e) {
                LOG.warn("Failed to send email for unhealthy applications", e);
            }
        }

    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedRateSchedule(initialDelay, period, TimeUnit.SECONDS);
    }
}
