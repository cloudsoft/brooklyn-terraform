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
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.group.BasicGroup;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriverLifecycleEffectorTasks;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.feed.function.FunctionFeed;
import org.apache.brooklyn.feed.function.FunctionPollConfig;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.SimpleProcessTaskFactory;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Maybe;
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

import static io.cloudsoft.terraform.TerraformCommons.SSH_MODE;
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
        if(Objects.equals(SSH_MODE, executionMode)) {
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
                    entity().getDriver().start(); // TODO look at logic around starting children
                }
                // TODO stop might work, but if not check and implement
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
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();

        addFeed(FunctionFeed.builder()
                .uniqueTag("scan-terraform-plan-and-output")
                .entity(this)
                .period(getConfig(TerraformCommons.POLLING_PERIOD))
                .poll(FunctionPollConfig.forSensor(PLAN).supplier(new PlanProvider(this)).name("refresh terraform plan")
                        .onResult(new PlanSuccessFunction())
                        .onFailure(new PlanFailureFunction()))
                .poll(FunctionPollConfig.forSensor(OUTPUT).supplier(new OutputProvider(this)).name("refresh terraform output")
                        .onResult(new OutputSuccessFunction())
                        .onFailure(new OutputFailureFunction()))
                .build());
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
        final String result = retryUntilLockAvailable("terraform show", () -> getDriver().runShowTask());
        Map<String, Object> state = StateParser.parseResources(result);
        sensors().set(TerraformConfiguration.STATE, state);
        Map<String, Object> resources = new HashMap<>(state);
        updateResources(resources, this, ManagedResource.class);
        updateDataResources(resources, DataResource.class);
        if (!resources.isEmpty()) { // new resource, new child must be created
            processResources(resources,this);
        }
    }

    private static Predicate<? super Entity> runningOrSync = c -> !c.sensors().getAll().containsKey(RESOURCE_STATUS) || (!c.sensors().get(RESOURCE_STATUS).equals("running") &&
            c.getParent().sensors().get(DRIFT_STATUS).equals(TerraformStatus.SYNC));

    private void updateResources(Map<String, Object> resources, Entity parent, Class<? extends TerraformResource> clazz) {
        List<Entity> childrenToRemove = new ArrayList<>();
        parent.getChildren().stream().filter(c -> clazz.isAssignableFrom(c.getClass())).forEach(c -> {
            if (runningOrSync.test(c)){
                c.sensors().set(RESOURCE_STATUS, "running");
            }
            if (resources.containsKey(c.getConfig(TerraformResource.ADDRESS))) { //child in resource set, update sensors
                ((TerraformResource) c).refreshSensors((Map<String, Object>) resources.get(c.getConfig(TerraformResource.ADDRESS)));
                resources.remove(c.getConfig(TerraformResource.ADDRESS));
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
    private void updateDataResources(Map<String, Object> resources, Class<? extends TerraformResource> clazz) {
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
            return ((TerraformConfigurationImpl) Entities.deproxy(entity)).retryUntilLockAvailable(name==null ? getClass().getSimpleName() : name, this::getWhenHasLock);
        }
    }

    public static class PlanProvider extends RetryingProvider<Map<String,Object>> {
        public PlanProvider(TerraformConfiguration entity) {
            super("terraform plan analysis", entity);
        }

        @Override
        protected Map<String, Object> getWhenHasLock() {
            return getDriver().runJsonPlanTask();
        }
    }

    private final class PlanSuccessFunction implements Function<Map<String, Object>, Map<String, Object>>  {
        @Nullable
        @Override
        public Map<String, Object> apply(@Nullable Map<String, Object> tfPlanStatus) {
            try {
                boolean driftChanged = false;
                if (sensors().getAll().containsKey(PLAN) && sensors().get(PLAN).containsKey(RESOURCE_CHANGES) &&
                        !sensors().get(PLAN).get(RESOURCE_CHANGES).equals(tfPlanStatus.get(RESOURCE_CHANGES))) {
                    // we had drift previously, and now either we have different drift or we don't have drift
                    driftChanged = true;
                }

                final TerraformStatus currentPlanStatus = (TerraformStatus) tfPlanStatus.get(PLAN_STATUS);
                final boolean ignoreDrift = !getConfig(TerraformConfiguration.TERRAFORM_DRIFT_CHECK);

                if (ignoreDrift || currentPlanStatus == TerraformStatus.SYNC) {
                    LOG.debug("Clearing problems and refreshing state because "+"state is "+tfPlanStatus+(currentPlanStatus == TerraformStatus.SYNC ? "" : " and ignoring drift"));
                    // plan status is SYNC so no errors, no ASYNC resources OR drift is ignored
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", Entities.REMOVE);
                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ERROR", Entities.REMOVE);
                    TerraformConfigurationImpl.this.sensors().remove(Sensors.newSensor(Object.class, "compliance.drift"));
                    TerraformConfigurationImpl.this.sensors().remove(Sensors.newSensor(Object.class, "tf.plan.changes"));
                    updateDeploymentState();

                } else if (TerraformConfiguration.TerraformStatus.ERROR.equals(tfPlanStatus.get(PLAN_STATUS))) {
                    LOG.debug("Setting problem because "+"state is "+tfPlanStatus);

                    ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ERROR",
                            tfPlanStatus.get(PLAN_MESSAGE) + ":" + tfPlanStatus.get("tf.errors"));
                    updateResourceStates(tfPlanStatus);

                } else if (!tfPlanStatus.get(PLAN_STATUS).equals(TerraformConfiguration.TerraformStatus.SYNC)) {
                    LOG.debug("Setting drift because "+"state is "+tfPlanStatus);

                    TerraformConfigurationImpl.this.sensors().set(DRIFT_STATUS, (TerraformStatus) tfPlanStatus.get(PLAN_STATUS));
                    if (tfPlanStatus.containsKey(RESOURCE_CHANGES)) {
                        ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Resources no longer match initial plan. Invoke 'apply' to synchronize configuration and infrastructure.");
                        updateDeploymentState(); // we are updating the resources anyway, because we still need to inspect our infrastructure
                        updateResourceStates(tfPlanStatus);
                    } else {
                        ServiceStateLogic.updateMapSensorEntry(TerraformConfigurationImpl.this, Attributes.SERVICE_PROBLEMS, "TF-ASYNC", "Outputs no longer match initial plan.This is not critical as the infrastructure is not affected. However you might want to invoke 'apply'.");
                    }
                    TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "compliance.drift"), tfPlanStatus);
                    TerraformConfigurationImpl.this.sensors().set(Sensors.newSensor(Object.class, "tf.plan.changes"), getDriver().runPlanTask());
                } else {
                    LOG.debug("No action because "+"state is "+tfPlanStatus);
                }

                if (driftChanged || !sensors().getAll().containsKey(DRIFT_STATUS) || !sensors().get(DRIFT_STATUS).equals(tfPlanStatus.get(PLAN_STATUS))) {
                    TerraformConfigurationImpl.this.sensors().set(DRIFT_STATUS, (TerraformStatus) tfPlanStatus.get(PLAN_STATUS));
                }
                lastCommandOutputs.put(PLAN.getName(), tfPlanStatus);
                return tfPlanStatus;
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
                    TerraformConfigurationImpl.this.getChildren().stream()
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
            ((ManagedResource) c).updateResourceState(); // TODO this method gets called twice when updating resources and updating them accoring to the plan, maybe fix at some point!!
        }
    }

    private final class PlanFailureFunction implements Function<Map<String, Object>, Map<String, Object>> {
        @Nullable
        @Override
        public Map<String, Object> apply(@Nullable Map<String, Object> input) {
            if (lastCommandOutputs.containsKey(PLAN.getName())) {
                return (Map<String, Object>) lastCommandOutputs.get(PLAN.getName());
            } else {
                return input;
            }
        }
    }

    public static class OutputProvider extends RetryingProvider<String> {
        public OutputProvider(TerraformConfiguration entity) {
            super("terraform output analysis", entity);
        }

        @Override
        protected String getWhenHasLock() {
            return getDriver().runOutputTask();
        }
    }

    private final class OutputSuccessFunction implements Function<String, String> {
        @Override
        public String apply(String output) {
            if (Strings.isBlank(output)) {
                return "No output is applied.";
            }
            try {
                Map<String, Map<String, Object>> result = new ObjectMapper().readValue(output, LinkedTreeMap.class);
                // remove sensors that were removed in the configuration
                List<AttributeSensor<?>> toRemove = new ArrayList<>();
                sensors().getAll().forEach((sK, sV) -> {
                    final String sensorName = sK.getName();
                    if(sensorName.startsWith(TF_OUTPUT_SENSOR_PREFIX+".") && !result.containsKey(sensorName.replace(TF_OUTPUT_SENSOR_PREFIX +".", ""))) {
                        toRemove.add(sK);
                    }
                });
                toRemove.forEach(os -> sensors().remove(os));

                for (String name : result.keySet()) {
                    final String sensorName = String.format("%s.%s", TF_OUTPUT_SENSOR_PREFIX, name);
                    final AttributeSensor sensor = Sensors.newSensor(Object.class, sensorName);
                    final Object currentValue = sensors().get(sensor);
                    final Object newValue = result.get(name).get("value");
                    if (!Objects.equals(currentValue, newValue)) {
                        sensors().set(sensor, newValue);
                    }
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Output does not have the expected format!");
            }
            lastCommandOutputs.put(OUTPUT.getName(), output);
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
        String executionMode = getConfig(TerraformCommons.TF_EXECUTION_MODE);
        if(Objects.equals(SSH_MODE, executionMode)) {
            return (TerraformDriver) super.getDriver();
        } else {
            return new TerraformContainerDriver(this);
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

    @Override
    @Effector(description = "Apply the Terraform configuration to the infrastructure. Changes made outside terraform are reset.")
    public void apply() {
        retryUntilLockAvailable("terraform apply", () -> { Objects.requireNonNull(getDriver()).runApplyTask(); return null; });
        plan();
    }

    @Override
    @Effector(description="Performs the Terraform plan command to show what would change (and refresh sensors).")
    public void plan() {
        new OutputSuccessFunction().apply(new OutputProvider(this).get());
        retryUntilLockAvailable("terraform plan", () -> getDriver().runPlanTask());
        new PlanSuccessFunction().apply(new PlanProvider(this).get());
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
        Map<String, Object> resources = MutableMap.of();
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
        SimpleProcessTaskFactory<?, ?, String, ?> command = getDriver().newCommandTaskFactory(false, null);
        if (command instanceof ContainerTaskFactory) {
            try {
                // delete all files in the volume created for this
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

            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                String ns = ((ContainerTaskFactory) command).getNamespace();
                LOG.error("Unable to delete container namespace '"+ns+" for "+this+" (ignoring): "+e);
            }
        }
    }

    @Override
    @Effector(description = "Performs Terraform apply again with the configuration provided via the provided URL. If an URL is not provided the original URL provided when this blueprint was deployed will be used." +
            "This is useful when the URL points to a GitHub or Artifactory release.")
    public void reinstallConfig(@EffectorParam(name = "configUrl", description = "URL pointing to the terraform configuration") @Nullable String configUrl) {
        if(StringUtils.isNotBlank(configUrl)) {
            config().set(TerraformCommons.CONFIGURATION_URL, configUrl);
        }
        retryUntilLockAvailable("reinstall configuration from "+configUrl, () -> {
            try {
                sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STARTING);
                ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);
                getDriver().customize();
                getDriver().launch();
                if (getChildren() == null || getChildren().isEmpty()) { // after a destroy
                    getDriver().postLaunch();
                    connectSensors();
                }
                sensors().set(Startable.SERVICE_UP, Boolean.TRUE);
                sensors().set(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING, Boolean.TRUE);
                return null;
            } catch (Exception e) {
                sensors().set(Startable.SERVICE_UP, Boolean.FALSE);
                sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
                throw e;
            } finally {
                sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
                ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
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
