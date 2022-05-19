package io.cloudsoft.terraform;


import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.entity.EntityInitializers;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.sensor.AbstractAddSensorFeed;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

@SuppressWarnings({"UnstableApiUsage", "deprecation", "unchecked"})
public class TerraformSensor<T> extends AbstractAddSensorFeed<T> {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformSensor.class);

    public TerraformSensor() {
    }

    public TerraformSensor(final ConfigBag parameters) {
        super(parameters);
    }

    public void apply(final EntityLocal entity) {
        AttributeSensor<String> sensor = (AttributeSensor<String>) addSensor(entity);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Adding terraform sensor {} to {}", initParam(SENSOR_NAME), entity);
        }

        ConfigBag configBag = ConfigBag.newInstanceCopying(initParams());

        final Boolean suppressDuplicates = EntityInitializers.resolve(configBag, SUPPRESS_DUPLICATES);
        final Duration logWarningGraceTimeOnStartup = EntityInitializers.resolve(configBag, LOG_WARNING_GRACE_TIME_ON_STARTUP);
        final Duration logWarningGraceTime = EntityInitializers.resolve(configBag, LOG_WARNING_GRACE_TIME);

        ((EntityInternal)entity).feeds().add(FunctionFeed.builder()
                .entity(entity)
                .period(initParam(SENSOR_PERIOD))
                .onlyIfServiceUp()
                .poll(new FunctionPollConfig<>(sensor)
                        .callable(new Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                return TerraformCommons.executeAndRetrieveOutput(entity, configBag);
                            }
                        })
                        .suppressDuplicates(Boolean.TRUE.equals(suppressDuplicates))
                        .logWarningGraceTimeOnStartup(logWarningGraceTimeOnStartup)
                        .logWarningGraceTime(logWarningGraceTime))
                .build());
    }
}
