package io.cloudsoft.terraform;

import com.google.common.reflect.TypeToken;
import io.cloudsoft.terraform.parser.StateParser;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.config.SetConfigKey;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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
        kubeJobConfig.put("workingDir", workdir + "/" + entity.getId());
    }

    private void clean(){
        String backupDir = kubeJobConfig.get("workingDir") + "/backup";
        kubeJobConfig.put("commands", MutableList.of("/bin/bash", "-c", "rm -rf " + backupDir
                + "; mkdir " + backupDir + "; mv * " + backupDir + "; mv " + backupDir + "/*.tfstate ."));
        DynamicTasks.queue(new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Moves existing configuration files to " + backupDir)
                .configure(kubeJobConfig)
                .newTask().asTask());
        DynamicTasks.waitForLast();
    }

    @Override
    public void customize() {
        LOG.info(" >> TerraformDockerDriver.customize() ...");
        kubeJobConfig.put("commands", MutableList.of("/bin/bash", "-c", "curl -L -f -o configuration.tf $TF_CFG_URL; " +
                "if grep -q \"No errors detected\" <<< $(unzip -t configuration.tf ); then mv configuration.tf configuration.zip && unzip configuration.zip ; fi"));
        Task<String> downloadTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Download and unpack configuration")
                .configure(kubeJobConfig)
                .newTask().asTask();

        kubeJobConfig.put("commands", MutableList.of("terraform" , "init", "-input=false"));
        Task<String> initTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Initialize terraform workspace")
                .configure(kubeJobConfig)
                .newTask().asTask();

        Task<Object> verifyTask =  Tasks.create("Verifying Terraform Workspace", () -> {
            try {
                String result =  initTask.get();
                if(result.contains(EMPTY_TF_CFG_WARN)) {
                    throw new IllegalStateException("Invalid or missing Terraform configuration." + result);
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException("Cannot retrieve result of command `terraform init -input=false`!", e);
            }
        }).asTask();

        DynamicTasks.queue(Tasks.builder()
                .displayName("Initializing terraform workspace")
                .add(downloadTask)
                .add(initTask)
                .add(verifyTask)
                .build());
        DynamicTasks.waitForLast();

    }

    @Override
    public void launch() {
        LOG.info(" >> TerraformDockerDriver.launch() ...");
        final Map<String,Object> planLog = runJsonPlanTask();
        Task<Object> verifyPlanTask = Tasks.create("Verify Plan", () -> {
            if (planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.ERROR) {
                throw new IllegalStateException(planLog.get(PLAN_MESSAGE) + ": " + planLog.get(PLAN_ERRORS));
            }
        }).asTask();
        Task<Object> checkAndApply =Tasks.create("Apply (if no existing deployment is found)", () -> {
            boolean deploymentExists = planLog.get(PLAN_STATUS) == TerraformConfiguration.TerraformStatus.SYNC;
            if (deploymentExists) {
                LOG.debug("Terraform plan exists!!");
            } else {
                runApplyTask();
            }
        }).asTask();

        DynamicTasks.queue(Tasks.builder()
                .displayName("Creating the planned infrastructure")
                .add(verifyPlanTask)
                .add(checkAndApply)
                .add(refreshTask())
                .build());
        DynamicTasks.waitForLast();

    }

    @Override // used for polling as well
    public Map<String, Object> runJsonPlanTask() {
        kubeJobConfig.put("commands", MutableList.of("terraform" , "plan", "-lock=false", "-input=false", "-json"));
        Task<String> planTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Creating the plan.")
                .configure(kubeJobConfig)
                .newTask().asTask();
        DynamicTasks.waitForLast();
        String result;
        try {
            result = planTask.get();
            return StateParser.parsePlanLogEntries(result);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan -json`!", e);
        }
    }

    @Override
    public void runApplyTask() {
        LOG.info(" >> TerraformDockerDriver.runApplyTask() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "apply", "-input=false", "-auto-approve"));
        Task<String> applyTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Applying terraform plan")
                .configure(kubeJobConfig)
                .newTask().asTask();
        DynamicTasks.waitForLast();
        entity.sensors().set(TerraformConfiguration.CONFIGURATION_APPLIED, new SimpleDateFormat("EEE, d MMM yyyy, HH:mm:ss").format(Date.from(Instant.now())));
        entity.getChildren().forEach(entity::removeChild);
    }

    private Task refreshTask() {
        LOG.info(" >> TerraformDockerDriver.runApplyTask() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "apply", "-refresh-only", "-input=false", "-auto-approve"));
        return new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Refreshing Terraform state")
                .configure(kubeJobConfig)
                .newTask().asTask();
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

    // Needed for extracting pure Terraform output for the tf.plan sensor
    @Override
    public String runPlanTask() {
        LOG.info(" >> TerraformDockerDriver.runPlanTask() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "plan", "-lock=false", "-input=false"));
        Task<String> planTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Inspecting terraform plan changes")
                .configure(kubeJobConfig)
                .newTask().asTask();
        DynamicTasks.waitForLast();
        try {
            return planTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan`!", e);
        }
    }

    @Override
    public String runOutputTask() {
        LOG.info(" >> TerraformDockerDriver.runOutputTask() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "output", "-json"));
        Task<String> outputTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Retrieving terraform outputs")
                .configure(kubeJobConfig)
                .newTask().asTask();
        DynamicTasks.waitForLast();
        try {
            return outputTask.get(); // TODO Should we allow this task to err ?
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan`!", e);
        }
    }

    @Override
    public String runShowTask() {
        LOG.info(" >> TerraformDockerDriver.runShowTask() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "show", "-json"));
        Task<String> showTask = new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Retrieve the most recent state snapshot")
                .configure(kubeJobConfig)
                .newTask().asTask();
        DynamicTasks.waitForLast();
        try {
            return showTask.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot retrieve result of command `terraform plan`!", e);
        }
    }

    @Override
    public int runDestroyTask() {
        LOG.info(" >> TerraformDockerDriver.runDestroyTask() ...");
        kubeJobConfig.put("commands", MutableList.of("terraform" , "apply", "--destroy", "-input=false ", "-auto-approve"));
        /*Task<String> destroyTask = */new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                .summary("Retrieve the most recent state snapshot")
                .configure(kubeJobConfig)
                .newTask().asTask();
        DynamicTasks.waitForLast();

        // TODO do we need this, should we make this a configuration ?
        boolean destroyTerraformWorkspace = true;
        if(destroyTerraformWorkspace) {
            kubeJobConfig.put("commands", MutableList.of("/bin/bash", "-c", "rm -rf " + kubeJobConfig.get("workingDir")));
            new ContainerTaskFactory.ConcreteContainerTaskFactory<String>()
                    .summary("Delete Terraform workspace")
                    .configure(kubeJobConfig)
                    .newTask().asTask();
            DynamicTasks.waitForLast();
        }
        return 0;
    }

    @Override
    public int runRemoveLockFileTask() {
        // TODO implement this
        return 0;
    }

    @Override
    public String getRunDir() {
        throw new NotImplementedException("TerraformDockerDriver.getRunDir() -- not needed");
    }

    @Override
    public String getInstallDir() {
        LOG.info(" >> TerraformDockerDriver.getInstallDir() ...");
        return null;
    }

    @Override
    public Location getLocation() {
        LOG.info(" TerraformDockerDriver.getLocation() -- not needed");
        return null;
    }

    @Override
    public EntityLocal getEntity() {
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
        clean();
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
