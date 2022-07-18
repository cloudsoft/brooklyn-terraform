package io.cloudsoft.terraform;

import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.config.SetConfigKey;
import org.apache.brooklyn.core.entity.EntityInitializers;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.apache.brooklyn.core.mgmt.BrooklynTaskTags.EFFECTOR_TAG;
import static org.apache.brooklyn.tasks.kubectl.ContainerCommons.CONTAINER_IMAGE;

/**
 *  Assume Terraform container has: { terraform, curl, unzip } installed
 *  Not a {@code SoftwareProcessDriver}.
 *  Implementing {@code TerraformDriver} , which in turn extends {@code SoftwareProcessDriver}
 *  provides the same API  that {@code TerraformConfigurationImpl} already works with.
 *  Obs:
 *   - preInstall& install methods are not needed
 *   TODO
 *   [ ] Implement pseudocode in this file
 *   [ ] Write tests
 *   [ ] Update documentation
 */
public class TerraformContainerDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformContainerDriver.class);
    protected final EntityLocal entity;

    private final Map<String, Object> kubeJobConfig = new HashMap<>();

    public TerraformContainerDriver(EntityLocal entity) {
        this.entity = entity;
    }

    private void prepare(){
        Map<String, String> env = getShellEnvironment((EntityInternal) entity);
        String cfgUrl = entity.config().get(TerraformCommons.CONFIGURATION_URL);
        env.put("TF_CFG_URL", cfgUrl);
        Map<String, Object> jobCfg = entity.getConfig(SetConfigKey.builder(new TypeToken<Map<String,Object>>()  {}, "kubejob.config").build());
        kubeJobConfig.putAll(jobCfg);
        kubeJobConfig.put("shell.env", env);

        String workdir = (String) jobCfg.get("workingDir"); // edit workingDir -> ${workingDir/entityID}, to have a common terraform workspace for all methods in the driver -> sensors and effectors won't need this
        kubeJobConfig.put("workingDir", workdir+"/"+entity.getId());
    }

    @Override
    public void customize() {
        LOG.info(" >> TerraformDockerDriver.customize() ...");
        kubeJobConfig.put("commands", MutableList.of("/bin/bash", "-c", "curl -L -f -o configuration.tf $TF_CFG_URL; " +
                "if grep -q \"No errors detected\" <<< $(unzip -t configuration.tf ); then mv configuration.tf configuration.zip && unzip configuration.zip ; fi"));
        Task<String> downloadTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Download and unpack configuration")
                .tag("CUSTOMIZE")
                .configure(kubeJobConfig)
                .newTask();
        DynamicTasks.queueIfPossible(downloadTask).orSubmitAsync(entity);  // TODO - simulate failure  - if task fails an exception is thrown here

        if(!downloadTask.isError()) {
            kubeJobConfig.put("commands", MutableList.of("terraform" , "init", "-input=false"));
            Task<String> initTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                    .summary("Initialize terraform workspace")
                    .configure(kubeJobConfig)
                    .newTask();
            DynamicTasks.queueIfPossible(initTask).orSubmitAsync(entity); // TODO - simulate failure  -   if task fails an exception is thrown here
            Object result = initTask.getUnchecked();
            List<String> res = (List<String>) result;
            while(!res.isEmpty() && Iterables.getLast(res).matches("namespace .* deleted\\s*")) res = res.subList(0, res.size()-1);

            String finalRes = res.isEmpty() ? "" : Iterables.getLast(res);
            if(!finalRes.contains("You may now begin working with Terraform.")) {
                throw new IllegalStateException("Terraform workspace initialization failed: " + finalRes);
            }
        }
    }

    @Override
    public void launch() {
        LOG.info(" >> TerraformDockerDriver.launch() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "apply", "-input=false", "-auto-approve"));
        Task<String> applyTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Executing Container Image: " + EntityInitializers.resolve((ConfigBag) entity.config(), CONTAINER_IMAGE))
                .tag(entity.getId() + "-" + EFFECTOR_TAG)
                .configure(kubeJobConfig)
                .newTask();
        // Execute task, inspect result, if apply failed, fail this activity -> throw exception

        // prev ->  SSH behaviour
        // we need this log too, for drift detection and reinstallConfig effector from TerraformConfigurationImpl
        // 1. verify plan: run jsonPlanCommand(), analyze output to check plan validity
        // 2. check plan status:  -> check deployment existence
        //  2.1 if plan_status = SYNC skip apply
        //  2.2 if plan_status != SYNC run applyCommand()
        // 3 refresh: run refreshCommand()
    }

    @Override
    public void postLaunch() {
        LOG.info(" >> TerraformDockerDriver.postLaunch() -- no needed.");
    }

    @Override
    public int destroy() {
        LOG.info(" >> TerraformDockerDriver.destroy() ...");
        return runDestroyTask();
    }

    @Override
    public Map<String, Object> runJsonPlanTask() {
        LOG.info(" >> TerraformDockerDriver.runJsonPlanTask() ...");
        // TODO
        ConfigBag configBag = null; //ConfigBag.newInstanceCopying(this.entity.config()).putAll(parameters);
        //configBag -> commands:  plan -lock=false -input=false -no-color -json
        Task<String> jsonPlanTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Executing Container Image: " + EntityInitializers.resolve((ConfigBag) entity.config(), CONTAINER_IMAGE))
                .tag(entity.getId() + "-" + EFFECTOR_TAG)
                .configure(configBag.getAllConfig())
                .newTask();
        // maybe reuse  jsonPlanCommand() inherited from TerraformDriver
        return null;
    }

    @Override
    public String runPlanTask() {
        LOG.info(" >> TerraformDockerDriver.runPlanTask() ...");
        // TODO
        ConfigBag configBag = null; //ConfigBag.newInstanceCopying(this.entity.config()).putAll(parameters);
        //configBag -> commands:  plan -lock=false -input=false -no-color
        Task<String> jsonPlanTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Executing Container Image: " + EntityInitializers.resolve((ConfigBag) entity.config(), CONTAINER_IMAGE))
                .tag(entity.getId() + "-" + EFFECTOR_TAG)
                .configure(configBag.getAllConfig())
                .newTask();
        // maybe reuse  planCommand() inherited from TerraformDriver
        // needed just to populate a sensor
        return null; // the output of the jsonPlanTask
    }

    @Override
    public void runApplyTask() {
        LOG.info(" >> TerraformDockerDriver.runApplyTask() ...");
        // check if we still need this and implement it properly.
        // TODO
        Task<String> applyTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Executing Container Image: " + EntityInitializers.resolve((ConfigBag) entity.config(), CONTAINER_IMAGE))
                .tag(entity.getId() + "-" + EFFECTOR_TAG)
                //.configure(configBag.getAllConfig())
                .newTask();
        //
    }

    @Override
    public String runOutputTask() {
        LOG.info(" >> TerraformDockerDriver.runOutputTask() ...");
        // TODO just create container task to run output command ...
        return null;
    }

    @Override
    public String runShowTask() {
        LOG.info(" >> TerraformDockerDriver.runShowTask() ...");
        // TODO just create container task to run output command ...
        return null;
    }

    @Override
    public int runDestroyTask() {
        LOG.info(" >> TerraformDockerDriver.runDestroyTask() ...");
        ConfigBag configBag = null; //ConfigBag.newInstanceCopying(this.entity.config()).putAll(parameters);
        //configBag -> commands: terraform apply --destroy -no-color -input=false -auto-approve
        Task<String> destroyTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Executing Container Image: " + EntityInitializers.resolve((ConfigBag) entity.config(), CONTAINER_IMAGE))
                .tag(entity.getId() + "-" + EFFECTOR_TAG)
                .configure(configBag.getAllConfig())
                .newTask();
        // check if user wants to keep the terraform workspace in the mounted volume  -> based on a config key that is not there yet
        // keep.terraform.workspace: default:false
        // if (!keep) {
        // add this task to a queue
        //configBag -> commands: rm -rf /tfws/entityID
        Task deleteTfWorkspaceTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Executing Container Image: " + EntityInitializers.resolve((ConfigBag) entity.config(), CONTAINER_IMAGE))
                .tag(entity.getId() + "-" + EFFECTOR_TAG)
                .configure(configBag.getAllConfig())
                .newTask();
        //}
        // intercept failure, fail this activity
        return 0;
    }

    @Override
    public String getRunDir() {
        LOG.info(" >> TerraformDockerDriver.getRunDir() -- not needed");
        return null;
    }

    @Override
    public String getInstallDir() {
        LOG.info(" >> TerraformDockerDriver.getInstallDir() ...");
        return null;
    }

    @Override
    public Location getLocation() {
        LOG.info(" >> TerraformDockerDriver.getLocation() -- not needed");
        return null;
    }

    @Override
    public EntityLocal getEntity() {
        LOG.info(" >> TerraformDockerDriver.getEntity() ...");
        return entity;
    }

    @Override
    public boolean isRunning() {
        LOG.info(" >> TerraformDockerDriver.isRunning() ...");
        return true;
    }

    @Override
    public void rebind() {
        // TODO not sure what to write here, or if there is anything to be done
        LOG.info(" >> TerraformDockerDriver.rebind() ...");
    }

    /**
     * prepare, install, customize, launch, post-launch
     * prepare & install not needed here
     */
    @Override
    public void start() {
        LOG.info(" >> TerraformDockerDriver.start() ...");
        prepare();
        customize();
        launch();
        postLaunch();
    }

    @Override
    public void restart() {
        // TODO might not be needed
        LOG.info(" >> TerraformDockerDriver.restart() ...");
    }

    @Override
    public void stop() {
        LOG.info(" >> TerraformDockerDriver.stop() ...");
        destroy();
    }

    @Override
    public void kill() {
        // TODO might not be needed, or what to do here
        LOG.info(" >> TerraformDockerDriver.kill() ...");
    }
}
