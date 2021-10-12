package io.cloudsoft.terraform;

import static com.google.common.collect.Maps.transformEntries;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.cloudsoft.terraform.parser.TerraformModel;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.effector.ssh.SshEffectorTasks;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.CommandPollConfig;
import org.apache.brooklyn.feed.ssh.SshFeed;
import org.apache.brooklyn.feed.ssh.SshPollValue;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskStub.ScriptReturnType;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bertramlabs.plugins.hcl4j.HCLParser;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Maps.EntryTransformer;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;

import com.fasterxml.jackson.databind.*;


public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);
    private static final String TF_OUTPUT_SENSOR_PREFIX = "tf.output";
    private static final Duration FEED_UPDATE_PERIOD = Duration.seconds(30);

    private SshFeed sshFeed;
    private Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
    private AtomicBoolean configurationChangeInProgress = new AtomicBoolean(false);

    private TerraformModel model = new TerraformModel();
    private ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void init() {
        super.init();
        // Exactly one of the two must have a value
        if ((Strings.isNonBlank(getConfig(CONFIGURATION_URL)) && Strings.isNonBlank(getConfig(CONFIGURATION_CONTENTS))) ||
                ((Strings.isBlank(getConfig(CONFIGURATION_URL)) && Strings.isBlank(getConfig(CONFIGURATION_CONTENTS))))) {
            throw new IllegalArgumentException("Exactly one of " +
                    CONFIGURATION_URL.getName() + " and " + CONFIGURATION_CONTENTS.getName() + " must be provided");
        }
        if (getAttribute(CONFIGURATION_IS_APPLIED) == null) {
            setConfigurationApplied(false);
        }
    }

    @Override
    public void rebind() {
        lastCommandOutputs = Collections.synchronizedMap(Maps.<String, Object>newHashMapWithExpectedSize(3));
        configurationChangeInProgress = new AtomicBoolean(false);
        super.rebind();
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

                    // TODO we should collect outputs then show immediately after a run or any change;
                    // TODO then _also_ _afterwards_ periodically do the items below one by one, otherwise they lock each other out
                    // eg:  tf.plan: Error: Error locking state: Error acquiring the state lock: resource temporarily unavailable

                    // TODO would be nice if this were json -- but use outputs for that
                    .poll(new CommandPollConfig<>(SHOW)
                            .env(env)
                            .command(getDriver().show())
                            .onSuccess(new ShowSuccessFunction())
                            .onFailure(new ShowFailureFunction()))

                    .poll(new CommandPollConfig<>(STATE)
                            .env(env)
                            .command(getDriver().refresh())
                            .onSuccess(new StateSuccessFunction())
                            .onFailure(new StateFailureFunction()))
                    .poll(new CommandPollConfig<>(PLAN)
                            .env(env)
                            .command(getDriver().plan())
                            .onSuccess(new PlanSuccessFunction())
                            .onFailure(new PlanFailureFunction()))

                    .poll(new CommandPollConfig<>(OUTPUT)
                            .env(env)
                            .command(getDriver().output())
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

    public TerraformModel getModel() {
        return model;
    }

    private final class ShowSuccessFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            String output = input.getStdout();
            if (Strings.isBlank(output)) {
                output = "No configuration is applied.";
            }
            lastCommandOutputs.put(SHOW.getName(), output);
            try {
                JsonNode outputNode = objectMapper.readTree(output);
                model.updateModel(null, outputNode);

            } catch (Exception e) {
                e.printStackTrace();
            }

            return output;
        }
    }

    private final class ShowFailureFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(SHOW.getName())) {
                return (String) lastCommandOutputs.get(SHOW.getName());
            } else {
                return input.getStderr();
            }
        }
    }

    private final class StateSuccessFunction implements Function<SshPollValue, Map<String, Object>> {
        @Override
        public Map<String, Object> apply(SshPollValue input) {
            try {

                Map<String, Object> state = getDriver().getState();
                lastCommandOutputs.put(STATE.getName(), state);
                return state;
            } catch (Exception e) {
                return ImmutableMap.<String, Object>of("ERROR", "Failed to parse state file.");
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
                return ImmutableMap.<String, Object>of("ERROR", "Failed to refresh state file.");
            }
        }
    }

    private final class PlanSuccessFunction implements Function<SshPollValue, String> {
        @Override
        public String apply(SshPollValue input) {
            String output = input.getStdout();
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
                Map<String, Map<String, Object>> result = new Gson().fromJson(output, LinkedTreeMap.class);
                for (String name : result.keySet()) {
                    final String sensorName = String.format("%s.%s", TF_OUTPUT_SENSOR_PREFIX, name);
                    final AttributeSensor sensor = Sensors.newSensor(Object.class, sensorName);
                    final Object currentValue = sensors().get(sensor);
                    final Object newValue = result.get(name).get("value");
                    if (!Objects.equal(currentValue, newValue)) {
                        sensors().set(sensor, newValue);
                    }
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
    @Effector(description = "Apply the Terraform configuration")
    public void apply() {
        LOG.info("Calling 'terraform apply tfplan'");
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = !configurationChangeInProgress.compareAndSet(false, true);
        // TODO: Is this really the right behaviour? Doesn't Terraform behave sensibly if the configuration is already correct?
        if (!configurationApplied && mayProceed) {
            try {
                String command = getDriver().makeTerraformCommand("apply -no-color -input=false tfplan");
                SshMachineLocation machine = Locations.findUniqueSshMachineLocation(getLocations()).get();
                ProcessTaskWrapper<Object> task = SshEffectorTasks.ssh(command)
                        .returning(ScriptReturnType.EXIT_CODE)
                        .requiringExitCodeZero()
                        .machine(machine)
                        .summary(command)
                        .newTask();
                DynamicTasks.queue(task).asTask();
                task.block();
                if (task.getExitCode() == 0) {
                    setConfigurationApplied(true);
                }
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new RuntimeException("Cannot destroy configuration: the configuration has already been applied");
        } else {
            throw new RuntimeException("Cannot destroy configuration: another operation is in progress");
        }
    }

    @Override
    @Effector(description = "Destroy the Terraform configuration")
    public void destroy() {
        LOG.info("Calling 'terraform destroy -auto-approve'");
        final boolean configurationApplied = isConfigurationApplied();
        final boolean mayProceed = !configurationChangeInProgress.compareAndSet(false, true);
        if (configurationApplied && mayProceed) {
            try {
                String command = getDriver().makeTerraformCommand("apply -destroy -auto-approve -no-color");
                SshMachineLocation machine = Locations.findUniqueSshMachineLocation(getLocations()).get();

                ProcessTaskWrapper<Object> task = SshEffectorTasks.ssh(command)
                        .environmentVariables(shellEnv())
                        .returning(ScriptReturnType.EXIT_CODE)
                        .requiringExitCodeZero()
                        .machine(machine)
                        .summary(command)
                        .newTask();
                DynamicTasks.queue(task).asTask();
                task.block();
                if (task.getExitCode() == 0) {
                    setConfigurationApplied(false);
                }
            } finally {
                configurationChangeInProgress.set(false);
            }
        } else if (!configurationApplied) {
            throw new RuntimeException("Cannot destroy configuration: the configuration has not been applied");
        } else {
            throw new RuntimeException("Cannot destroy configuration: another operation is in progress");
        }
    }

    private synchronized void setConfigurationApplied(boolean isConfigurationApplied) {
        sensors().set(CONFIGURATION_IS_APPLIED, isConfigurationApplied);
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
