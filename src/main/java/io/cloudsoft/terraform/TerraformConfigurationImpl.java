package io.cloudsoft.terraform;

import static com.google.common.collect.Maps.transformEntries;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.CommandPollConfig;
import org.apache.brooklyn.feed.ssh.SshFeed;
import org.apache.brooklyn.feed.ssh.SshPollValue;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Maps.EntryTransformer;
import com.google.gson.internal.LinkedTreeMap;

import com.fasterxml.jackson.databind.*;


public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);
    private static final String TF_OUTPUT_SENSOR_PREFIX = "tf.output";
    private static final Duration FEED_UPDATE_PERIOD = Duration.seconds(30);

    private SshFeed sshFeed;
    private Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
    private AtomicBoolean configurationChangeInProgress = new AtomicBoolean(false);

    @Override
    public void init() {
        super.init();
        // Exactly one of the two must have a value
        if (Strings.isNonBlank(getConfig(CONFIGURATION_URL)) ^ Strings.isNonBlank(getConfig(CONFIGURATION_CONTENTS))) {
            if (getAttribute(CONFIGURATION_IS_APPLIED) == null) {
                sensors().set(CONFIGURATION_IS_APPLIED, false);
            }
        } else {
            throw new IllegalArgumentException("Exactly one of " +
                    CONFIGURATION_URL.getName() + " or " + CONFIGURATION_CONTENTS.getName() + " must be provided");
        }
    }

    @Override
    public void rebind() {
        lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
        configurationChangeInProgress = new AtomicBoolean(false);
        super.rebind();
    }

    @Override
    protected void preStop() {
        super.preStop();
        getChildren().forEach(c -> c.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING));
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

        Maybe<SshMachineLocation> machine = Locations.findUniqueSshMachineLocation(getLocations());
        if (machine.isPresent()) {
            Map<String, String> env = shellEnv();
            addFeed(sshFeed = SshFeed.builder()
                    .entity(this)
                    .period(FEED_UPDATE_PERIOD)
                    .machine(machine.get())
                    // TODO consider doing this using sequential tasks
                    //  TODO also condition reload of state based on the output of plan
                    // TODO we should collect outputs then show immediately after a run or any change;
                    // TODO then _also_ _afterwards_ periodically do the items below one by one, otherwise they lock each other out
                    // eg:  tf.plan: Error: Error locking state: Error acquiring the state lock: resource temporarily unavailable
                    // TODO would be nice if this were json -- but use outputs for that
                    .poll(new CommandPollConfig<>(STATE)
                            .env(env)
                            .command(getDriver().showCommand())
                            .onSuccess(new StateSuccessFunction())
                            .onFailure(new StateFailureFunction()))
                    .poll(new CommandPollConfig<>(PLAN)
                            .env(env)
                            .command(getDriver().planCommand())
                            .onSuccess(new PlanSuccessFunction())
                            .onFailure(new PlanFailureFunction()))
                    .poll(new CommandPollConfig<>(OUTPUT)
                            .env(env)
                            .command(getDriver().outputCommand())
                            .onSuccess(new OutputSuccessFunction())
                            .onFailure(new OutputFailureFunction()))
                    .build());
        }
    }

    @Override
    protected void disconnectSensors() {
        disconnectServiceUpIsRunning();
        if (sshFeed != null) sshFeed.stop();
        super.disconnectSensors();
    }

    private final class StateSuccessFunction implements Function<SshPollValue, Map<String, Object>> {
        @Override
        public Map<String, Object> apply(SshPollValue input) {
            try {
                Map<String, Object>  state = StateParser.parseResources(input.getStdout());
                Map<String, Object>  resources = new HashMap<>(state);
                getChildren().forEach(c -> {
                    if(resources.containsKey(c.getConfig(ManagedResource.ADDRESS))) { //child in resource set, update sensors
                        ((ManagedResource)c).refreshSensors((Map<String,Object>)resources.get(c.getConfig(ManagedResource.ADDRESS)));
                        resources.remove(c.getConfig(ManagedResource.ADDRESS));
                    } else {
                        getChildren().remove(c); // else  child not in resource set (deleted by terraform -> remove child)
                    }
                });
                if(!resources.isEmpty()) { // new resource, new child must be created
                    resources.forEach((resourceName, resourceContents) -> {
                        Map<String,Object> contentsMap = (Map<String,Object>) resourceContents;
                        addChild(
                                EntitySpec.create(ManagedResource.class)
                                        .configure(ManagedResource.STATE_CONTENTS, contentsMap)
                                        .configure(ManagedResource.TYPE, contentsMap.get("resource.type").toString())
                                        .configure(ManagedResource.PROVIDER, contentsMap.get("resource.provider").toString())
                                        .configure(ManagedResource.ADDRESS, contentsMap.get("resource.address").toString())
                                        .configure(ManagedResource.NAME, contentsMap.get("resource.name").toString())
                        );
                    }) ;
                }
                lastCommandOutputs.put(STATE.getName(), state); // TODO kinda stupid to parse again, but until this works properly is better to have the state in the UI
                return state;
            } catch (Exception e) {
                return ImmutableMap.of("ERROR","Problem refreshing state: " .concat(input.getStderr()));
            }
        }
    }

    private final class StateFailureFunction implements Function<SshPollValue, Map<String, Object>> {
        @SuppressWarnings("unchecked")
        @Override
        public Map<String, Object> apply(SshPollValue input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(STATE.getName())) {
                return (Map<String, Object>) lastCommandOutputs.get(STATE.getName());
            } else {
               return ImmutableMap.of("ERROR", "Failed to refresh state.");
            }
        }
    }

    private final class PlanSuccessFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            String output = input.getStdout();
            if(!output.contains("No changes.")) { // TODO or contains Drift detected - improve this
                sensors().set(CONFIGURATION_IS_APPLIED, false);
                getDriver().runRefreshTask();
                // user is asked to execute 'terraform apply'
                // also at this point in the UI things should start blinking
            }
            lastCommandOutputs.put(PLAN.getName(), output);
            return output;
        }
    }

    private final class PlanFailureFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(PLAN.getName())) {
                return (String) lastCommandOutputs.get(PLAN.getName());
            } else {
                return input.getStderr();
            }
        }
    }

    /**
     * Output looks like
     * <pre>
     * {
     *   "address": {
     *     "sensitive": false,
     *     "type": "string",
     *     "value": "172.31.2.35"
     *   },
     *   ...
     * }
     * </pre>
     */
    private final class OutputSuccessFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            String output = input.getStdout();
            if (output != null) {
                try {
                    Map<String, Map<String, Object>> result = new ObjectMapper().readValue(output, LinkedTreeMap.class);
                    for (String name : result.keySet()) {
                        final String sensorName = String.format("%s.%s", TF_OUTPUT_SENSOR_PREFIX, name);
                        final AttributeSensor sensor = Sensors.newSensor(Object.class, sensorName);
                        final Object currentValue = sensors().get(sensor);
                        final Object newValue = result.get(name).get("value");
                        if (!Objects.equal(currentValue, newValue)) {
                            sensors().set(sensor, newValue);
                        }
                    }
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Output does not have the expected format!");
                }
            }
            if (Strings.isBlank(output)) {
                return "No output is applied.";
            }
            lastCommandOutputs.put(OUTPUT.getName(), output);
            return output;
        }
    }

    private final class OutputFailureFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(OUTPUT.getName())) {
                return (String) lastCommandOutputs.get(OUTPUT.getName());
            } else {
                return input.getStderr();
            }
        }
    }

    @Override
    public Class<?> getDriverInterface() {
        return TerraformDriver.class;
    }

    @Override
    public TerraformDriver getDriver() {
        return (TerraformDriver) super.getDriver();
    }

    @Override
    @Effector(description = "Apply the Terraform configuration to the infrastructure. Changes made outside terraform are reset.")
    public void apply() {
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = !configurationChangeInProgress.compareAndSet(false, true);
        if (!configurationApplied && mayProceed) {
            try {
                getDriver().runApplyTask();
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new IllegalStateException("Cannot apply terraform plan: the configuration has already been applied.");
        } else {
            throw new IllegalStateException("Cannot apply configuration: another operation is in progress.");
        }
    }

    @Override
    @Effector(description = "Destroy the Terraform configuration")
    public void destroy() {
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = !configurationChangeInProgress.compareAndSet(false, true);
        if (configurationApplied && mayProceed) {
            try {
                getDriver().runDestroyTask();
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new IllegalStateException("Cannot destroy configuration: the configuration has not been applied.");
        } else {
            throw new IllegalStateException("Cannot destroy configuration: another operation is in progress.");
        }
    }

    @Override
    public void destroyTarget(ManagedResource child) {
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = !configurationChangeInProgress.compareAndSet(false, true);
        if (configurationApplied && mayProceed) {
            try {
                child.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING);
                final String destroyTargetCommand = getDriver().destroyCommand().concat(" -target=")
                    .concat(child.getConfig(ManagedResource.TYPE)).concat(".").concat(child.getConfig(ManagedResource.NAME));
                int result = getDriver().runDestroyTargetTask(destroyTargetCommand);
                if (result == 0 ) {
                    child.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
                    getParent().removeChild(child);
                } else {
                    child.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
                }
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new IllegalStateException("Cannot destroy target: the configuration has not been applied.");
        } else {
            throw new IllegalStateException("Cannot destroy target: another operation is in progress.");
        }
    }

    @Override
    public synchronized boolean isConfigurationApplied() {
        return getAttribute(CONFIGURATION_IS_APPLIED);
    }

    private Map<String, String> shellEnv() {
        return transformEntries(config().get(SHELL_ENVIRONMENT), new EnvironmentTransformer());
    }

    private static class EnvironmentTransformer implements EntryTransformer<String, Object, String> {
        @Override
        public String transformEntry(String key, Object value) {
            return TypeCoercions.coerce(value, String.class);
        }
    }
}
