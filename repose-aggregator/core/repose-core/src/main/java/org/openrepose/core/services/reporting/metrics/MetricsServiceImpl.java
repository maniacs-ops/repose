/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
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
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.core.services.reporting.metrics;

import com.yammer.metrics.core.*;
import com.yammer.metrics.reporting.GraphiteReporter;
import com.yammer.metrics.reporting.JmxReporter;
import org.openrepose.commons.config.manager.UpdateListener;
import org.openrepose.core.services.config.ConfigurationService;
import org.openrepose.core.services.healthcheck.HealthCheckService;
import org.openrepose.core.services.healthcheck.HealthCheckServiceProxy;
import org.openrepose.core.services.healthcheck.Severity;
import org.openrepose.core.services.reporting.metrics.config.GraphiteServer;
import org.openrepose.core.services.reporting.metrics.config.MetricsConfiguration;
import org.openrepose.core.spring.ReposeJmxNamingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * This factory class generates Yammer Metrics objects & exposes them through JMX & Graphite.  Any metric classes which
 * might be used by multiple components should have a factory method off this class.
 * <p/>
 * To ensure no namespace collisions between clusters & nodes, the {@link org.openrepose.core.spring.ReposeJmxNamingStrategy} object is used.  This object
 * also provides the ObjectName for any Spring-managed MBeans.
 * <p/>
 * <h1>Custom MXBeans </h1>
 * <p/>
 * If you need to register a MXBean, please follow the example of the ConfigurationInformation class.  While its not an
 * MXBean (its an MBean) much of its construction is usable for MXBeans.  It contains the following aspects:
 * <ul>
 * <li> Declares itself as a @ManagedResource.  Use the same objectName format.
 * <li> Implements a MBean interface (MXBeans are named *MXBean).
 * <li> Declares JMX viewable methods with @ManagedOperation.
 * </ul>
 * Additionally, this can also be helpful:
 * <ul>
 * <li><a href="http://docs.oracle.com/javase/tutorial/jmx/mbeans/mxbeans.html">http://docs.oracle.com/javase/tutorial/jmx/mbeans/mxbeans.html</a>  In particular the part about the @ConstructorProperites
 * annotations I believe prevents you from having to mess with CompositeData when returning custom objects through
 * a MXBean.
 * </ul>
 * <p/>
 * <h1>Functional Tests for instrumented filters</h1>
 * The functional tests contained in
 * repose/repose-aggregator/tests/functional-tests/src/test/groovy/features/core/powerfilter/ResponseCodeJMXTest.groovy
 * provide an example on how you might verify your instrumentation.
 */
@Named
public class MetricsServiceImpl implements MetricsService {
    public static final String DEFAULT_CONFIG_NAME = "metrics.cfg.xml";

    private static final Logger LOG = LoggerFactory.getLogger(MetricsServiceImpl.class);
    private static final String metricsServiceConfigReport = "MetricsServiceReport";

    private final ConfigurationService configurationService;
    private final HealthCheckServiceProxy healthCheckServiceProxy;
    private final MetricsCfgListener metricsCfgListener = new MetricsCfgListener();
    private MetricsRegistry metrics;
    private JmxReporter jmx;
    private List<GraphiteReporter> listGraphite = new ArrayList<>();
    private ReposeJmxNamingStrategy reposeStrat;
    private boolean enabled;

    @Inject
    public MetricsServiceImpl(
            ConfigurationService configurationService,
            HealthCheckService healthCheckService,
            ReposeJmxNamingStrategy reposeStrat
    ) {
        this.configurationService = configurationService;
        this.reposeStrat = reposeStrat;
        this.healthCheckServiceProxy = healthCheckService.register();

        this.metrics = new MetricsRegistry();

        //There are tests that don't start the JMX if the metrics service isn't enabled...
        this.jmx = new JmxReporter(metrics);
        jmx.start(); //But it needs to be started if there's no configs

        this.enabled = true;
    }

    @PostConstruct
    public void init() {

        healthCheckServiceProxy.reportIssue(metricsServiceConfigReport, "Metrics Service Configuration Error", Severity.BROKEN);
        URL xsdURL = getClass().getResource("/META-INF/schema/metrics/metrics.xsd");
        configurationService.subscribeTo(DEFAULT_CONFIG_NAME, xsdURL, metricsCfgListener, MetricsConfiguration.class);

        // The Metrics config is optional so in the case where the configuration listener doesn't mark it initialized
        // and the file doesn't exist, this means that the Metrics service will load its own default configuration
        // and the initial health check error should be cleared.
        try {
            if (!metricsCfgListener.isInitialized() && !configurationService.getResourceResolver().resolve("metrics.cfg.xml").exists()) {
                healthCheckServiceProxy.resolveIssue(metricsServiceConfigReport);
            }
        } catch (IOException io) {
            LOG.error("Error attempting to search for {}", DEFAULT_CONFIG_NAME, io);
        }
    }

    public void addGraphiteServer(String host, int port, long period, String prefix)
            throws IOException {
        GraphiteReporter graphite = new GraphiteReporter(metrics,
                prefix,
                MetricPredicate.ALL,
                new GraphiteReporter.DefaultSocketProvider(host, port),
                Clock.defaultClock());

        graphite.start(period, TimeUnit.SECONDS);

        synchronized (listGraphite) {
            listGraphite.add(graphite);
        }
    }

    public void shutdownGraphite() {
        synchronized (listGraphite) {
            for (GraphiteReporter graphite : listGraphite) {
                graphite.shutdown();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean b) {
        this.enabled = b;
    }

    @Override
    public Meter newMeter(Class klass, String name, String scope, String eventType, TimeUnit unit) {
        return metrics.newMeter(makeMetricName(klass, name, scope), eventType, unit);
    }

    @Override
    public Counter newCounter(Class klass, String name, String scope) {
        return metrics.newCounter(makeMetricName(klass, name, scope));
    }

    @Override
    public Timer newTimer(Class klass, String name, String scope, TimeUnit duration, TimeUnit rate) {
        return metrics.newTimer(makeMetricName(klass, name, scope), duration, rate);
    }

    @Override
    public TimerByCategory newTimerByCategory(Class klass, String scope, TimeUnit duration, TimeUnit rate) {
        return new TimerByCategoryImpl(this, klass, scope, duration, rate);
    }

    @Override
    public MeterByCategory newMeterByCategory(Class klass, String scope, String eventType, TimeUnit unit) {
        return new MeterByCategoryImpl(this, klass, scope, eventType, unit);
    }

    @Override
    public MeterByCategorySum newMeterByCategorySum(Class klass, String scope, String eventType, TimeUnit unit) {
        return new MeterByCategorySum(this, klass, scope, eventType, unit);
    }

    @PreDestroy
    @Override
    public void destroy() {
        configurationService.unsubscribeFrom(DEFAULT_CONFIG_NAME, metricsCfgListener);

        metrics.shutdown();
        jmx.shutdown();

        shutdownGraphite();
    }

    /**
     * This creates a metric name based off the local JVM JMX Naming strategy.
     * TODO: This might not actually be what we want. We might want to make metrics based on the local node....
     *
     * @param klass
     * @param name
     * @param scope
     * @return
     */
    private MetricName makeMetricName(Class klass, String name, String scope) {
        return new MetricName(reposeStrat.getJmxPrefix() + klass.getPackage().getName(),
                klass.getSimpleName(),
                name, scope);
    }

    private class MetricsCfgListener implements UpdateListener<MetricsConfiguration> {

        private boolean initialized = false;

        @Override
        public void configurationUpdated(MetricsConfiguration metricsC) {
            // we are reinitializing the graphite servers
            shutdownGraphite();

            //We're going to reset the JMX stuff
            jmx.shutdown();

            if (metricsC.getGraphite() != null) {
                for (GraphiteServer gs : metricsC.getGraphite().getServer()) {
                    try {
                        addGraphiteServer(gs.getHost(),
                                gs.getPort().intValue(),
                                gs.getPeriod(),
                                gs.getPrefix());
                    } catch (IOException e) {
                        LOG.error("Error adding a Graphite server: {}", gs.getHost(), e);
                    }
                }
            }

            healthCheckServiceProxy.resolveIssue(metricsServiceConfigReport);
            setEnabled(metricsC.isEnabled());

            //Only start JMX if it's enabled...
            if (metricsC.isEnabled()) {
                jmx.start();
            }
            initialized = true;
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }
}
