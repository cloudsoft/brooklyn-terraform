package io.cloudsoft.terraform;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.effector.ssh.SshEffectorTasks;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.feed.ssh.SshFeed;
import org.apache.brooklyn.feed.ssh.SshPollConfig;
import org.apache.brooklyn.feed.ssh.SshPollValue;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskStub.ScriptReturnType;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.Duration;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Duration FEED_UPDATE_PERIOD = Duration.seconds(30);

    private SshFeed sshFeed;

    private FunctionFeed functionFeed;

    private final AtomicBoolean configurationChangeInProgress = new AtomicBoolean(false);

    private final AtomicBoolean configurationIsApplied = new AtomicBoolean(false);

    private final Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.<String, Object> newHashMapWithExpectedSize(3));

    @Override
    public void init() {
        super.init();
        checkConfiguration();
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

        functionFeed = FunctionFeed.builder()
                .entity(this)
                .period(FEED_UPDATE_PERIOD)
                .poll(new FunctionPollConfig<Object, Boolean>(CONFIGURATION_IS_APPLIED)
                    .callable(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            return configurationIsApplied.get();
                        }
                    }))
                .build();

        Maybe<SshMachineLocation> machine = Locations.findUniqueSshMachineLocation(getLocations());
        if (machine.isPresent()) {
            sshFeed = SshFeed.builder()
                    .entity(this)
                    .period(FEED_UPDATE_PERIOD)
                    .machine(machine.get())
                    .poll(new SshPollConfig<String>(SHOW)
                            .command(getDriver().makeTerraformCommand("show -no-color"))
                            .onSuccess(new Function<SshPollValue, String>() {
                                @Override
                                public String apply(SshPollValue input) {
                                    String output = input.getStdout();
                                    lastCommandOutputs.put(SHOW.getName(), output);

                                    return output;
                                }})
                            .onFailure(new Function<SshPollValue, String>() {
                                @Override
                                public String apply(SshPollValue input) {
                                    if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(SHOW.getName()))
                                        return (String) lastCommandOutputs.get(SHOW.getName());
                                    else
                                        return input.getStderr();
                                }}))
                    .poll(new SshPollConfig<Map<String, Object>>(STATE)
                            .command(getDriver().makeTerraformCommand("refresh -no-color"))
                            .onSuccess(new Function<SshPollValue, Map<String, Object>>() {
                                @Override
                                public Map<String, Object> apply(SshPollValue input) {
                                    try {
                                        Map<String, Object> state = getDriver().getState();
                                        lastCommandOutputs.put(STATE.getName(), state);

                                        return state;
                                    } catch (Exception e) {
                                        return ImmutableMap.<String, Object> of("ERROR", "Failed to parse state file.");
                                    }
                                }})
                            .onFailure(new Function<SshPollValue, Map<String, Object>>() {
                                @SuppressWarnings("unchecked")
                                @Override
                                public Map<String, Object> apply(SshPollValue input) {
                                    if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(STATE.getName()))
                                        return (Map<String, Object>) lastCommandOutputs.get(STATE.getName());
                                    else
                                        return ImmutableMap.<String, Object> of("ERROR", "Failed to refresh state file.");
                                }}))
                    .poll(new SshPollConfig<String>(PLAN)
                            .command(getDriver().makeTerraformCommand("plan -no-color"))
                            .onSuccess(new Function<SshPollValue, String>() {
                                @Override
                                public String apply(SshPollValue input) {
                                    String output = input.getStdout();
                                    lastCommandOutputs.put(PLAN.getName(), output);

                                    return output;
                                }})
                            .onFailure(new Function<SshPollValue, String>() {
                                @Override
                                public String apply(SshPollValue input) {
                                    if (configurationChangeInProgress.get() && lastCommandOutputs.containsKey(PLAN.getName()))
                                        return (String) lastCommandOutputs.get(PLAN.getName());
                                    else
                                        return input.getStderr();
                                }}))
                    .build();
        }
    }

    @Override
    public void disconnectSensors() {
        disconnectServiceUpIsRunning();
        if (sshFeed != null) sshFeed.stop();
        if (functionFeed != null) functionFeed.stop();
        super.disconnectSensors();
    }

    private void checkConfiguration() {
        String configurationUrl = getConfig(CONFIGURATION_URL);
        String configurationContents = getConfig(CONFIGURATION_CONTENTS);

        // Exactly one of the two must have a value
        if (Strings.isBlank(configurationUrl) == Strings.isBlank(configurationContents))
            throw new IllegalArgumentException("Exactly one of the two must have a value: '"
                    + CONFIGURATION_URL.getName() + "' or '" + CONFIGURATION_CONTENTS.getName() + "'.");
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
    @Effector(description="Performs the Terraform apply command which will create all of the infrastructure specified by the configuration.")
    public void apply() {
        if (!configurationIsApplied.get() && !configurationChangeInProgress.getAndSet(true)) {
            try {
                String command = getDriver().makeTerraformCommand("apply -no-color");
                SshMachineLocation machine = Locations.findUniqueSshMachineLocation(getLocations()).get();

                ProcessTaskWrapper<Object> task = SshEffectorTasks.ssh(command)
                        .returning(ScriptReturnType.EXIT_CODE)
                        .requiringExitCodeZero()
                        .machine(machine)
                        .summary(command)
                        .newTask();

                DynamicTasks.queue(task).asTask();
                task.block();

                if (task.getExitCode() == 0)
                    configurationIsApplied.set(true);
            } finally {
                configurationChangeInProgress.set(false);
            }
        }
    }

    @Override
    @Effector(description="Performs the Terraform destroy command which will destroy all of the infrastructure that has been previously created by the configuration.")
    public void destroy() {
        if (configurationIsApplied.get() && !configurationChangeInProgress.getAndSet(true)) {
            try {
                String command = getDriver().makeTerraformCommand("destroy -force -no-color");
                SshMachineLocation machine = Locations.findUniqueSshMachineLocation(getLocations()).get();

                ProcessTaskWrapper<Object> task = SshEffectorTasks.ssh(command)
                        .returning(ScriptReturnType.EXIT_CODE)
                        .requiringExitCodeZero()
                        .machine(machine)
                        .summary(command)
                        .newTask();

                DynamicTasks.queue(task).asTask();
                task.block();

                if (task.getExitCode() == 0)
                    configurationIsApplied.set(false);
            } finally {
                configurationChangeInProgress.set(false);
            }
        }
    }
}
