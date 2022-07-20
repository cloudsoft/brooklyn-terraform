package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.config.MapConfigKey;
import org.apache.brooklyn.tasks.kubectl.ContainerCommons;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.system.SimpleProcessTaskFactory;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 *  Assume Terraform container has: { terraform, curl, unzip } installed
 *  Not a {@code SoftwareProcessDriver}.
 *  Implementing {@code TerraformDriver} , which in turn extends {@code SoftwareProcessDriver}
 *  provides the same API  that {@code TerraformConfigurationImpl} already works with.
 *  Obs:
 *   - preInstall& install methods are not needed
 *   TODO
 *   [ ] Write tests
 *   [ ] Update documentation
 */
public class TerraformContainerDriver implements TerraformDriver {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformContainerDriver.class);
    public static final MapConfigKey<Object> KUBEJOB_CONFIG = new MapConfigKey.Builder(Object.class, "kubejob.config").build();

    protected final EntityLocal entity;

//    private final Map<String, Object> kubeJobConfig = new HashMap<>();

    public TerraformContainerDriver(EntityLocal entity) {
        this.entity = entity;
    }

    @Override
    public SimpleProcessTaskFactory<?, ?, String, ?> newCommandTaskFactory(boolean withEnvVars, String command) {
        MutableMap<Object, Object> config = MutableMap.of()
                .add(getEntity().getConfig(KUBEJOB_CONFIG))
                .add(ConfigBag.newInstance().configure(ContainerCommons.WORKING_DIR, getTerraformActiveDir()).getAllConfig());

        ContainerTaskFactory<?,String> tf = ContainerTaskFactory.newInstance()
                .bashScriptCommands(command)
                .allowingNonZeroExitCode(false)
                .configure(config)
                .returningStdout();
        if (withEnvVars) tf.environmentVariables(getShellEnvironment());
        return tf;
    }

    @Override
    public String makeTerraformCommand(String argument) {
        return "terraform "+argument;
    }

    @Override
    public String getTerraformActiveDir() {
        Map<String, Object> kubecfg = getEntity().getConfig(KUBEJOB_CONFIG);
        String baseDir = null;
        if (kubecfg!=null) baseDir = Strings.toString(kubecfg.get(ContainerCommons.WORKING_DIR.getName()));
        if (baseDir==null) baseDir = ".";
        baseDir = Strings.removeAllFromEnd(baseDir, "/", "\\") + "/";
        baseDir = "brooklyn-terraform/"+getEntity().getApplicationId()+"/"+getEntity().getId()+"/active/";
        return baseDir;
    }

    @Override
    public void customize() {
        LOG.info(" >> TerraformDockerDriver.customize() ...");

        moveConfigurationFilesToBackupDir();

        // TODO what if URL is classpath (which it often is)

        String cfgUrl = entity.config().get(TerraformCommons.CONFIGURATION_URL);
        DynamicTasks.queue(newCommandTaskFactory(false, Strings.lines(
                        "curl -L -f -o configuration.tf $TF_CFG_URL",
                        "if grep -q \"No errors detected\" <<< $(unzip -t configuration.tf ); then",
                        "  mv configuration.tf configuration.zip && unzip configuration.zip",
                        "fi"))
                .summary("Download and unpack configuration")
                .environmentVariable("TF_CFG_URL", cfgUrl).newTask());

        runTerraformInitAndVerifyTask();
        DynamicTasks.waitForLast();
    }

    @Override
    public void launch() { TerraformDriver.super.launch(); }

    @Override
    public void postLaunch() {}

    @Override
    public Location getLocation() {
        // TODO can we throw here?
        LOG.trace(" TerraformDockerDriver.getLocation() -- not needed");
        return null;
    }

    @Override
    public EntityLocal getEntity() {
        return entity;
    }

    @Override
    public boolean isRunning() {
        LOG.trace(" >> TerraformDockerDriver.isRunning() ...");
        return true;
    }

    @Override
    public void rebind() {
        LOG.debug(" >> TerraformDockerDriver.rebind() ... nothing needed");
    }

    /**
     * prepare, install, customize, launch, post-launch
     * prepare & install not needed here
     */
    @Override
    public void start() {
        LOG.info(" >> TerraformDockerDriver.start() ...");
        customize();
        launch();
        postLaunch();
    }

    @Override
    public void restart() {
        LOG.info(" >> TerraformDockerDriver.restart() ... just doing apply");
        ((TerraformConfiguration)getEntity()).apply();
    }

    @Override public void stop() { TerraformDriver.super.stop(); }

    @Override
    public void kill() {
        LOG.debug(" >> TerraformDockerDriver.kill() ... same as stop");
        stop();
    }
}
