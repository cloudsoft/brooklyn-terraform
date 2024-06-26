package io.cloudsoft.terraform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.gson.internal.LinkedTreeMap;
import io.cloudsoft.terraform.entity.DataResource;
import io.cloudsoft.terraform.entity.ManagedResource;
import io.cloudsoft.terraform.entity.TerraformResource;
import io.cloudsoft.terraform.parser.EntityParser;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.workflow.steps.CustomWorkflowStep;
import org.apache.brooklyn.entity.group.BasicGroup;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriverLifecycleEffectorTasks;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.SimpleProcessTaskFactory;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.text.StringEscapes;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.time.CountdownTimer;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.cloudsoft.terraform.TerraformDriver.*;
import static io.cloudsoft.terraform.entity.StartableManagedResource.RESOURCE_STATUS;
import static io.cloudsoft.terraform.parser.EntityParser.processResources;

public class TerraformConfigurationImpl extends SoftwareProcessImpl implements TerraformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformConfigurationImpl.class);
    private static final String TF_OUTPUT_SENSOR_PREFIX = "tf.output";

    private Map<String, Object> lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
    private AtomicReference<Thread> configurationChangeInProgress = new AtomicReference(null);

    private Boolean applyDriftComplianceCheckToResources = false;

    @Override
    public void init() {
        super.init();
    }

    // TODO check this.
    @Override
    protected SoftwareProcessDriverLifecycleEffectorTasks getLifecycleEffectorTasks() {
        String executionMode = getConfig(TerraformCommons.TF_EXECUTION_MODE);

        if (Objects.equals(SSH_MODE, executionMode)) {
            return getConfig(LIFECYCLE_EFFECTOR_TASKS);

        } else {
            return new SoftwareProcessDriverLifecycleEffectorTasks(){
                @Override
                protected Map<String, Object> obtainProvisioningFlags(MachineProvisioningLocation<?> location) {
                    throw new  NotImplementedException("Should not be called!");
                }

                @Override
                protected Task<MachineLocation> provisionAsync(MachineProvisioningLocation<?> location) {
                    throw new  NotImplementedException("Should not be called!");
                }

                @Override
                protected void startInLocations(Collection<? extends Location> locations, ConfigBag parameters) {
                    upsertDriver(false).start();
                    // TODO look at logic around starting children
                }

                // stop and other things should simply be inherited

            };
        }
    }

    @Override
    public void rebind() {
        lastCommandOutputs = Collections.synchronizedMap(Maps.newHashMapWithExpectedSize(3));
        configurationChangeInProgress = new AtomicReference(null);
        super.rebind();
    }

    @Override
    protected void preStop() {
        super.preStop();
        getChildren().forEach(c -> c.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPING));
    }

    @Override
    protected void postStop() {
        getChildren().forEach(c -> c.sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED));

        // when stopped, unmanage all the things we created; we do not need to remove them as children
        getChildren().forEach(child -> {
            if (child instanceof BasicGroup){
                child.getChildren().stream().filter(gc -> gc instanceof TerraformResource)
                                .forEach(Entities::unmanage);
            }
            if (child instanceof TerraformResource){
                Entities.unmanage(child);
            }
        });
    }

    @Override
    public void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

        addFeed(FunctionFeed.builder()
                .uniqueTag("scan-terraform-plan-and-output")
                .entity(this)
                .period(getConfig(TerraformCommons.POLLING_PERIOD))
                .poll(FunctionPollConfig.forMultiple().name("Refresh terraform")
                        .supplier(new RefreshTerraformModelAndSensors(this, true))
                        .onException(new QueueAndRunFailedTasks()) )
                .build());
    }

    static class QueueAndRunFailedTasks implements Function<Throwable, Void> {
        @Override
        public Void apply(Throwable e) {
            return DynamicTasks.queue(Tasks.fail("Error refreshing terraform", e)).getUnchecked();
        }
    }

    @Override
    protected void disconnectSensors() {
        disconnectServiceUpIsRunning();
        feeds().forEach(feed -> feed.stop());
        super.disconnectSensors();
    }

    /**
     *  This method is called only when TF and AMP are in sync
     *  No need to update state when no changes were detected.
     *  Since `terraform plan` is the only command reacting to changes, it makes sense entities to change according to its results.
     */
    private void updateDeploymentState() {
        final String statePull = retryUntilLockAvailable("terraform state pull", () -> getDriver().runStatePullTask());
        sensors().set(TerraformConfiguration.TF_STATE, statePull);

        // TODO would be nice to deprecate this as 'show' is a bit more expensive than other things
        final String show = retryUntilLockAvailable("terraform show", () -> getDriver().runShowTask());
        Map<String, Map<String,Object>> state = StateParser.parseResources(show);
        sensors().set(TerraformConfiguration.STATE, state);

        if (!Boolean.FALSE.equals(config().get(TERRAFORM_RESOURCE_ENTITIES_ENABLED))) {
            Map<String, Map<String, Object>> resources = MutableMap.copyOf(state);
            updateResources(resources, this, ManagedResource.class);
            updateDataResources(resources, DataResource.class);
            if (!resources.isEmpty()) { // new resource, new child must be created
                processResources(resources, this);
            }
        }
    }

    private static Predicate<? super Entity> runningOrSync = c -> !c.sensors().getAll().containsKey(RESOURCE_STATUS) || (!c.sensors().get(RESOURCE_STATUS).equals("running") &&
            c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC));

    private void updateResources(Map<String, Map<String,Object>> resourcesToSensors, Entity parent, Class<? extends TerraformResource> clazz) {
        List<Entity> childrenToRemove = new ArrayList<>();
        parent.getChildren().stream().filter(c -> clazz.isAssignableFrom(c.getClass())).forEach(c -> {
            if (runningOrSync.test(c)){
                c.sensors().set(RESOURCE_STATUS, "running");
            }
            if (resourcesToSensors.containsKey(c.getConfig(TerraformResource.ADDRESS))) { //child in resource set, update sensors
                ((TerraformResource) c).refreshSensors(resourcesToSensors.get(c.getConfig(TerraformResource.ADDRESS)));
                resourcesToSensors.remove(c.getConfig(TerraformResource.ADDRESS));
            } else {
                childrenToRemove.add(c);
            }
        });
        if (!childrenToRemove.isEmpty()) {
            LOG.debug("Removing "+clazz+" resources no longer reported by Terraform at "+parent+": "+childrenToRemove);
            childrenToRemove.forEach(Entities::unmanage);   // unmanage nodes that are no longer relevant (removing them as children causes leaks)
        }
    }

    /**
     * Updates Data resources
     */
    private void updateDataResources(Map<String, Map<String,Object>> resources, Class<? extends TerraformResource> clazz) {
        EntityParser.getDataResourcesGroup(this).ifPresent(c -> updateResources(resources, c, clazz));
    }

    protected abstract static class RetryingProvider<T> implements Supplier<T> {
        String name = null;
        TerraformConfiguration entity;

        // kept for backwards compatibility / rebind
        TerraformDriver driver;

        protected RetryingProvider(String name, TerraformConfiguration entity) {
            this.name = name;
            this.entity = entity;
        }

        protected TerraformDriver getDriver() {
            if (entity==null) {
                // force migration to preferred persistence
                this.entity = (TerraformConfiguration) driver.getEntity();
                this.driver = null;
                if (name==null) name = getClass().getSimpleName();
                return getDriver();
            }
            return entity.getDriver();
        }

        protected abstract T getWhenHasLock();

        @Override
        public T get() {
            return deproxied(entity).retryUntilLockAvailable(name==null ? getClass().getSimpleName() : name, this::getWhenHasLock);
        }
    }

    private static TerraformConfigurationImpl deproxied(TerraformConfiguration entity) {
        return (TerraformConfigurationImpl) Entities.deproxy(entity);
    }

    public static class RefreshTerraformModelAndSensors extends RetryingProvider<Void> {
        private final boolean doTerraformRefresh;

        public RefreshTerraformModelAndSensors(TerraformConfiguration entity, boolean doTerraformRefresh) {
            super("refresh terraform model and plan", entity);
            this.doTerraformRefresh = doTerraformRefresh;
        }

        @Override
        protected Void getWhenHasLock() {
            PlanProcessingFunction planProcessor = new PlanProcessingFunction(entity);
            planProcessor.ignoreStateChangeBecauseGoingToReplan = true;
            boolean tfCloudMode = Boolean.TRUE.equals(entity.config().get(TERRAFORM_CLOUD_MODE));
            String filename = tfCloudMode ? null : "../"+ Identifiers.makeRandomId(8)+".plan";
            String planOutputJsonLines = tfCloudMode ? "" : getDriver().runJsonPlanTask(doTerraformRefresh, filename, null);
            Map<String, Object> planSensorValue = planProcessor.apply(planOutputJsonLines);
            boolean statePullNeeded = false;

            planProcessor.ignoreStateChangeBecauseGoingToReplan = false;

            /*
             * If something changes in AWS which contradicts our plan, we expect to get _both_ planned_change and resource_drift.
             * If we then refresh and plan again, we only get a planned_change.
             * If something changes in AWS which does not contract our plan, we get only a resource_drift,
             * in which case normally we want to refresh. The next plan will not report anything.
             * If our configuration changes, then all we get is a planned_change.
             *
             * Thus we want to check on a resource-by-resource basis, if it _only_ has resource_drift, but no changes planned,
             * then we want to refresh it.
             * But if it has planned_change (with or without resource_drift) we should _not_ refresh it.
             *
             * Annoyingly `terraform apply -refresh-only <plan_file>` ignores the refresh only.
             */

            Set<String> driftDetectedSomeResourcesAreStateChangeOnly = (Set) planSensorValue.get(RESOURCES_DRIFT_DETECTED_STATE_ONLY);

            if (driftDetectedSomeResourcesAreStateChangeOnly!=null && !driftDetectedSomeResourcesAreStateChangeOnly.isEmpty()) {

                LOG.debug("Apply state change only updates to resources: "+driftDetectedSomeResourcesAreStateChangeOnly);
                if (!((Map) planSensorValue.get(RESOURCES_CHANGES_PLANNED)).isEmpty()) {
                    // but some resources had planned changes, so need to first construct a plan which is changes only
                    planOutputJsonLines = getDriver().runJsonPlanTask(doTerraformRefresh, filename,
                            driftDetectedSomeResourcesAreStateChangeOnly.stream().map(r ->
                                    " -target="+ StringEscapes.BashStringEscapes.wrapBash(r)).collect(Collectors.joining())
                    );
                    // annoyingly if we come into this block, *outputs* will not be refreshed by applying this plan
                    // IE an `apply -target=X` _will_ update outputs, but a `plan -target=X -out=Plan` then `apply Plan` will not
                    // seems there is no way to do an apply to reliably update outputs and resources without planned changes
                    // according to https://github.com/hashicorp/terraform/issues/22864 outputs _should_ be updated _if_ all contributing resources are targeted;
                    // but in tests, that doesn't happen at least in the case where the outputs are static
                    planSensorValue = planProcessor.apply(planOutputJsonLines);
                }

                if (!((Map) planSensorValue.get(RESOURCES_CHANGES_PLANNED)).isEmpty()) {
                    // unlikely - would only happen if between the first plan above and the second plan above something changed
                    // meaning TF found planned_changes to the resources which in the first plan didn't have planned changes
                    LOG.debug("Attempt to replan on state-change-only resources generated resources that now have planned changes: "+planSensorValue.get(RESOURCES_CHANGES_PLANNED));
                    // skip the application of that plan; just delete the plan
                    if (filename!=null) {
                        getDriver().runQueued(getDriver().newCommandTaskFactory(true,
                                        getDriver().makeCommandInTerraformActiveDir(
                                                "rm " + filename))
                                .summary("clean up")
                                .newTask().asTask());
                    }

                } else {
                    getDriver().runQueued(getDriver().newCommandTaskFactory(true,
                                    getDriver().makeCommandInTerraformActiveDir(
                                            getDriver().prependTerraformExecutable(getDriver().applySubcommand(filename))
                                                    + (filename!=null ? " && " + "rm " + filename : "")))
                            .summary("terraform apply (limited to state changes) and clean up")
                            .newTask().asTask());
                }

                // replan, if in this block
                planOutputJsonLines = getDriver().runJsonPlanTask(doTerraformRefresh);
                statePullNeeded = true;

            } else {
                // either all resources in sync or have planned changes or output changed; in this case do not refresh,
                // we can simply use the plan that was found
                if (filename!=null) {
                    getDriver().runQueued(getDriver().newCommandTaskFactory(true,
                                    getDriver().makeCommandInTerraformActiveDir(
                                            "rm " + filename))
                            .summary("clean up")
                            .newTask().asTask());
                }
            }

            if (statePullNeeded) {
                planSensorValue = planProcessor.apply(planOutputJsonLines);
            }

            entity.sensors().set(PLAN, planSensorValue);
            deproxied(entity).refreshOutput(false);
            return null;
        }
    }

    private String refreshOutput(boolean refresh) {
        return sensors().set(OUTPUT, new OutputSuccessFunction(this).apply(getDriver().runOutputTask(refresh)));
    }

    private static final class PlanProcessingFunction implements Function<String, Map<String, Object>>  {
        private final TerraformConfiguration entity;
        boolean ignoreStateChangeBecauseGoingToReplan = false;

        public PlanProcessingFunction(TerraformConfiguration entity) {
            this.entity = entity;
        }

        @Nullable
        @Override
        public Map<String, Object> apply(@Nullable String tfPlanJson) {
            try {
                Map<String, Object> tfPlanStatusDetailFromLogEntries = StateParser.parsePlanLogEntries(entity, tfPlanJson);

                final TerraformStatus currentPlanStatus = (TerraformStatus) tfPlanStatusDetailFromLogEntries.get(PLAN_STATUS);
                final boolean ignoreDrift = !entity.getConfig(TerraformConfiguration.TERRAFORM_DRIFT_CHECK);

                if (TerraformConfiguration.TerraformStatus.ERROR.equals(currentPlanStatus)) {
                    LOG.debug("Setting problem because "+"state is "+tfPlanStatusDetailFromLogEntries);

                    ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", Entities.REMOVE);
                    ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS,
                            "TF-ERROR", tfPlanStatusDetailFromLogEntries.get(PLAN_MESSAGE) + ":" + tfPlanStatusDetailFromLogEntries.get("tf.errors"));
                    updateResourceStates(tfPlanStatusDetailFromLogEntries);

                } else if (ignoreStateChangeBecauseGoingToReplan && TerraformStatus.STATE_CHANGE.equals(currentPlanStatus)) {
                    LOG.debug("Found local-state-only drift. Not updating sensors as this will handled locally and then normally re-run.");
                    return tfPlanStatusDetailFromLogEntries;

                } else if (ignoreDrift || currentPlanStatus == TerraformStatus.SYNC) {
                    LOG.debug("Clearing problems and refreshing state because "+"state is "+tfPlanStatusDetailFromLogEntries+(currentPlanStatus == TerraformStatus.SYNC ? "" : " and ignoring drift"));
                    // plan status is SYNC so no errors, no ASYNC resources OR drift is ignored
                    ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", Entities.REMOVE);
                    ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ERROR", Entities.REMOVE);
                    ((EntityInternal)entity).sensors().remove(Sensors.newSensor(Object.class, "compliance.drift"));
                    ((EntityInternal)entity).sensors().remove(Sensors.newSensor(Object.class, "tf.plan.changes"));
                    deproxied(entity).updateDeploymentState();

                } else if (!TerraformConfiguration.TerraformStatus.SYNC.equals(currentPlanStatus)) {
                    LOG.debug("Setting drift because " + "state is " + tfPlanStatusDetailFromLogEntries);

                    if (tfPlanStatusDetailFromLogEntries.containsKey(RESOURCE_CHANGES)) {
                        ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Resources no longer match initial plan. Invoke 'apply' to synchronize configuration and infrastructure.");
                        ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ERROR", Entities.REMOVE);

                        deproxied(entity).updateDeploymentState(); // we are updating the resources anyway, because we still need to inspect our infrastructure
                        updateResourceStates(tfPlanStatusDetailFromLogEntries);
                    } else {
                        ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Outputs no longer match initial plan.This is not critical as the infrastructure is not affected. However you might want to invoke 'apply'.");
                        ServiceStateLogic.updateMapSensorEntry(entity, Attributes.SERVICE_PROBLEMS, "TF-ERROR", Entities.REMOVE);
                    }

                    entity.sensors().set(Sensors.newSensor(Object.class, "compliance.drift"), tfPlanStatusDetailFromLogEntries);
                    entity.sensors().set(Sensors.newSensor(Object.class, "tf.plan.changes"), entity.getDriver().runPlanTask());

                } else {
                    // shouldn't be possible to come here
                    LOG.debug("No action because "+"state is "+tfPlanStatusDetailFromLogEntries);
                }

                boolean driftChanged = entity.sensors().get(PLAN)!=null && !Objects.equals(entity.sensors().get(PLAN).get(RESOURCE_CHANGES), tfPlanStatusDetailFromLogEntries.get(RESOURCE_CHANGES));
                if (driftChanged || !Objects.equals(entity.sensors().get(DRIFT_STATUS), currentPlanStatus)) {
                    // republished whenever drift has changed, or if status has changed
                    // (deliberately republish same value, if the resources involved are different)
                    entity.sensors().set(DRIFT_STATUS, currentPlanStatus);
                }

                deproxied(entity).lastCommandOutputs.put(PLAN.getName(), tfPlanStatusDetailFromLogEntries);
                return tfPlanStatusDetailFromLogEntries;

            } catch (Exception e) {
                LOG.error("Unable to process terraform plan", e);
                throw Exceptions.propagate(e);
            }
        }

        private void updateResourceStates(Map<String, Object> tfPlanStatus) {
            Object hasChanges = tfPlanStatus.get(RESOURCE_CHANGES);
            LOG.debug("Terraform plan updating: " + tfPlanStatus + ", changes: "+hasChanges);
            if (hasChanges!=null) {
                ((List<Map<String, Object>>) hasChanges).forEach(changeMap -> {
                    String resourceAddr = changeMap.get("resource.addr").toString();
                    entity.getChildren().stream()
                            .filter(c -> c instanceof ManagedResource)
                            .filter(c -> resourceAddr.equals(c.config().get(TerraformResource.ADDRESS)))
                            .forEach(this::checkAndUpdateResource);
                });
            }
        }

        private void checkAndUpdateResource(Entity c) {
            if (!c.sensors().get(RESOURCE_STATUS).equals("changed") && !c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC)) {
                c.sensors().set(RESOURCE_STATUS, "changed");
            }
            // this method gets called twice when updating resources and updating them accoring to the plan, maybe fix at some point
            ((ManagedResource) c).updateResourceState();
        }
    }

//    private final class PlanFailureFunction implements Function<String, Map<String, Object>> {
//        @Nullable
//        @Override
//        public Map<String, Object> apply(@Nullable String input) {
//            // TODO better handle failure; this just spits back a parse as best it can
//            if (lastCommandOutputs.containsKey(PLAN.getName())) {
//                return (Map<String, Object>) lastCommandOutputs.get(PLAN.getName());
//            } else {
//                return StateParser.parsePlanLogEntries(input);
//            }
//        }
//    }

    private static final class OutputSuccessFunction implements Function<String, String> {
        TerraformConfiguration entity;
        private OutputSuccessFunction(TerraformConfiguration entity) {
            this.entity = entity;
        }
        @Override
        public String apply(String output) {
            if (Strings.isBlank(output)) {
                return "No output is applied.";
            }
            try {
                Map<String, Map<String, Object>> result = new ObjectMapper().readValue(output, LinkedTreeMap.class);
                // remove sensors that were removed in the configuration
                List<AttributeSensor<?>> toRemove = new ArrayList<>();
                entity.sensors().getAll().forEach((sK, sV) -> {
                    final String sensorName = sK.getName();
                    if(sensorName.startsWith(TF_OUTPUT_SENSOR_PREFIX+".") && !result.containsKey(sensorName.replace(TF_OUTPUT_SENSOR_PREFIX +".", ""))) {
                        toRemove.add(sK);
                    }
                });
                toRemove.forEach(os -> ((EntityInternal)entity).sensors().remove(os));

                for (String name : result.keySet()) {
                    final String sensorName = String.format("%s.%s", TF_OUTPUT_SENSOR_PREFIX, name);
                    final AttributeSensor sensor = Sensors.newSensor(Object.class, sensorName);
                    final Object currentValue = entity.sensors().get(sensor);
                    final Object newValue = result.get(name).get("value");
                    if (!Objects.equals(currentValue, newValue)) {
                        entity.sensors().set(sensor, newValue);
                    }
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Output does not have the expected format!");
            }
            deproxied(entity).lastCommandOutputs.put(OUTPUT.getName(), output);
            return output;
        }
    }

    private final class OutputFailureFunction implements Function<String, String> {
        @Override
        public String apply(String input) {
            if (lastCommandOutputs.containsKey(OUTPUT.getName())) {
                return (String) lastCommandOutputs.get(OUTPUT.getName());
            } else {
                return input;
            }
        }
    }

    @Override
    public Class<?> getDriverInterface() {
        return TerraformDriver.class;
    }

    @Override
    public TerraformDriver getDriver() {
        return upsertDriver(false);
    }

    private transient TerraformDriver terraformDriver;
    private transient Object terraformDriverCreationLock = new Object();

    protected TerraformDriver upsertDriver(boolean replace) {
        if (terraformDriver!=null && !replace) return terraformDriver;

        synchronized (terraformDriverCreationLock) {
            if (terraformDriver!=null && !replace) return terraformDriver;

            String executionMode = getConfig(TerraformCommons.TF_EXECUTION_MODE);

            if (Objects.equals(SSH_MODE, executionMode)) {
                terraformDriver = (TerraformDriver) super.getDriver();

            } else if (Objects.equals(LOCAL_MODE, executionMode)) {
                terraformDriver = new TerraformLocalDriver(this);

            } else if (Objects.equals(KUBE_MODE, executionMode)) {
                terraformDriver = new TerraformContainerDriver(this);

            } else {
                // shouldn't happen as config has a default
                LOG.warn("Config '" + TerraformCommons.TF_EXECUTION_MODE.getName() + "' returned null " + this + "; using default kubernetes");
                terraformDriver = new TerraformContainerDriver(this);
            }

            return terraformDriver;
        }
    }

    <V> V retryUntilLockAvailable(String summary, Callable<V> runWithLock) {
        return retryUntilLockAvailable(summary, runWithLock, Duration.ONE_MINUTE, Duration.FIVE_SECONDS);
    }

    <V> V retryUntilLockAvailable(String summary, Callable<V> runWithLock, Duration timeout, Duration retryFrequency) {
        CountdownTimer timerO = timeout.isNegative() ? null : timeout.countdownTimer();
        while(true) {
            Object hadLock = null;
            Thread lockOwner = configurationChangeInProgress.get();
            if (lockOwner!=null) {
                if (lockOwner.equals(Thread.currentThread())) hadLock = Thread.currentThread();
                Task task = Tasks.current();
                while (hadLock==null && task != null) {
                    if (lockOwner.equals(task.getThread())) hadLock = task+" / "+task.getThread();
                    task = task.getSubmittedByTask();
                }
            }
            boolean gotLock = false;
            if (hadLock==null) {
                gotLock = configurationChangeInProgress.compareAndSet(null, Thread.currentThread());
            }
            if (hadLock!=null || gotLock) {
                if (gotLock) {
                    LOG.debug("Acquired lock for '"+summary+"' (thread "+Thread.currentThread()+")");
                } else {
                    LOG.debug("Already had lock for '"+summary+"', from "+hadLock);
                }
                try {
                    return runWithLock.call();
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                } finally {
                    if (gotLock) {
                        configurationChangeInProgress.set(null);
                        LOG.debug("Cleared lock for '"+summary+"' (thread "+Thread.currentThread()+")");
                    }
                }
            } else {
                if (timerO!=null && timerO.isExpired()) {
                    throw new IllegalStateException("Cannot perform "+summary+": operation timed out before lock available (is another change or refresh in progress?)");
                }
                try {
                    Tasks.withBlockingDetails("Waiting on terraform lock (change or refresh in progress?), owned by "+configurationChangeInProgress.get()+"; sleeping then retrying "+summary,
                            () -> { Time.sleep(retryFrequency); return null; } );
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
            }
        }
    }

    protected Maybe<Object> runWorkflow(ConfigKey<CustomWorkflowStep> key) {
        return workflowTask(key).transformNow(t ->
                DynamicTasks.queueIfPossible(t).orSubmitAsync(this).andWaitForSuccess() );
    }

    protected Maybe<Task<Object>> workflowTask(ConfigKey<CustomWorkflowStep> key) {
        CustomWorkflowStep workflow = getConfig(key);
        if (workflow==null) return Maybe.absent();
        return workflow.newWorkflowExecution(this, key.getName().toLowerCase(),
                null /* could getInput from workflow, and merge shell environment here */).getTask(true);
    }

    @Override
    @Effector(description = "Apply the Terraform configuration to the infrastructure. Changes made outside terraform are reset.")
    public void apply() {
        runWorkflow(PRE_APPLY_WORKFLOW);
        retryUntilLockAvailable("terraform apply", () -> { Objects.requireNonNull(getDriver()).runApplyTask(); return null; });
        runWorkflow(POST_APPLY_WORKFLOW);
        plan();
    }

    @Override
    @Effector(description="Performs the Terraform plan command to show what would change (and refresh sensors).")
    public void plan() {
        planInternal(true);
    }

    protected void planInternal(boolean refresh) {
        runWorkflow(PRE_PLAN_WORKFLOW);
        new RefreshTerraformModelAndSensors(this, refresh).get();
    }

    @Override
    @Effector(description = "Force a re-discovery of resources (clearing all first)")
    public void rediscoverResources() {
        LOG.debug("Forcibly clearing children nodes of "+this+"; will re-discover from plan");
        removeDiscoveredResources();

        // now re-plan, which should re-populate if healthy
        plan();
    }

    @Override
    public void removeDiscoveredResources() {
        Map<String, Map<String,Object>> resources = MutableMap.of();
        updateResources(resources, this, ManagedResource.class);
        updateDataResources(resources, DataResource.class);
    }

    @Override
    @Effector(description = "Delete any terraform lock file (may be needed if AMP was interrupted; done automatically for stop, as we manage mutex locking)")
    public void clearTerraformLock() {
        retryUntilLockAvailable("clear terraform lock", () -> {
            getDriver().runRemoveLockFileTask();
            return null;
        }, Duration.seconds(-1), Duration.seconds(1));
    }

    @Override
    @Effector(description = "Destroy the Terraform configuration")
    public void destroyTerraform() {
        retryUntilLockAvailable("terraform destroy", () -> {
            getDriver().destroy(false);
            return null;
        }, Duration.seconds(-1), Duration.seconds(1));
    }

    @Override
    public void onManagementDestroying() {
        super.onManagementDestroying();
        SimpleProcessTaskFactory<?, ?, String, ?> command = null;
        String ns = null;
        try {
            if( getDriver()!=null) command = getDriver().newCommandTaskFactory(false, null);
            if (command instanceof ContainerTaskFactory) {
                // delete all files in the volume created for this
                ns = ((ContainerTaskFactory) command).getNamespace();
                getExecutionContext().submit(
                        ((ContainerTaskFactory)command).setDeleteNamespaceAfter(true).summary("Deleting files and namespace").bashScriptCommands(
                            "cd ..",
                            "rm -rf "+getId(),
                            "cd ..",
                            "rmdir "+getApplicationId()+" || echo other entities exist in this application, not deleting application folder")
                                .newTask()
                    ).get();

                // previously we just deleted the namespace
//                getExecutionContext().submit("ensuring container namespace is deleted", () -> {
//                    ((ContainerTaskFactory) command).doDeleteNamespace(true, false);
//                }).get();

            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            LOG.error("Unable to delete container namespace '"+ns+" for "+this+" (ignoring): "+e);
        }
    }

    @Override
    @Effector(description = "Performs Terraform apply again with the configuration provided via the provided URL. If an URL is not provided the original URL provided when this blueprint was deployed will be used." +
            "This is useful when the URL points to a GitHub or Artifactory release.")
    public void reinstallConfig(@EffectorParam(name = "configUrl", description = "URL pointing to the terraform configuration") @Nullable String configUrl) {
        reinstallConfigInternal(configUrl);
    }

    public void reinstallConfigInternal(@Nullable String configUrl) {
        if(StringUtils.isNotBlank(configUrl)) {
            config().set(TerraformCommons.CONFIGURATION_URL, configUrl);
        }
        retryUntilLockAvailable("reinstall configuration from "+configUrl, () -> {
            try {
                DynamicTasks.queueIfPossible(Tasks.builder()
                        .displayName("Prepare latest configuration files")
                        .body(() -> {
                            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STARTING);
                            ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);
                            getDriver().customize();
                        }).build()).orSubmitAsync(this).andWaitForSuccess();

                getDriver().launch();

                DynamicTasks.queueIfPossible(Tasks.builder()
                        .displayName("Update service state sensors")
                        .body(() -> {
                            if (!connectedSensors) {
                                connectSensors();
                            }

                            sensors().set(Startable.SERVICE_UP, Boolean.TRUE);
                            sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
                            ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
                            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
                        }).build()).orSubmitAsync(this).andWaitForSuccess();

                return null;
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                sensors().set(Startable.SERVICE_UP, Boolean.FALSE);
                sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
                ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
                throw e;
            } finally {
            }
        });
    }

    @Override
    public Boolean isApplyDriftComplianceToResources(){
        return applyDriftComplianceCheckToResources;
    }

    @Override
    public void setApplyDriftComplianceToResources(Boolean doApply){
        applyDriftComplianceCheckToResources = doApply;
    }
}
