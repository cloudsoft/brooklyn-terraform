package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.config.MapConfigKey;
import org.apache.brooklyn.tasks.kubectl.ContainerCommons;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskFactory;
import org.apache.brooklyn.util.core.task.system.ProcessTaskStub;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.core.task.system.SimpleProcessTaskFactory;
import org.apache.brooklyn.util.core.task.system.internal.SystemProcessTaskFactory;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
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

    protected final EntityLocal entity;

//    private final Map<String, Object> kubeJobConfig = new HashMap<>();

    public TerraformContainerDriver(EntityLocal entity) {
        this.entity = entity;
    }

    @Override
    public SimpleProcessTaskFactory<?, ?, String, ?> newCommandTaskFactory(boolean withEnvVars, String command) {
        MutableMap<Object, Object> config = MutableMap.of()
                .add(getEntity().getConfig(TerraformCommons.KUBEJOB_CONFIG))
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
        Map<String, Object> kubecfg = getEntity().getConfig(TerraformCommons.KUBEJOB_CONFIG);
        String baseDir = null;
        if (kubecfg!=null) baseDir = Strings.toString(kubecfg.get(ContainerCommons.WORKING_DIR.getName()));
        if (baseDir==null) baseDir = ".";
        baseDir = Strings.removeAllFromEnd(baseDir, "/", "\\") + "/";
        baseDir = "brooklyn-terraform/"+getEntity().getApplicationId()+"/"+getEntity().getId()+"/active/";
        return baseDir;
    }

    @Override
    public void copyTo(InputStream tfStream, String target) {
        File f = Os.newTempFile("terraform-" + getEntity().getId(), "dat");
        // TODO we need to get the namespace and pod
        String namespace = "???";
        String pod = "???";
        // https://medium.com/@nnilesh7756/copy-directories-and-files-to-and-from-kubernetes-container-pod-19612fa74660
        ProcessTaskWrapper<Object> t = DynamicTasks.queue(new SystemProcessTaskFactory.ConcreteSystemProcessTaskFactory<String>(
                "kubectl cp " + f.getAbsolutePath() + " " + namespace + "/" + pod + ":" + target)
                .summary("Copying data to " + target)
                .returning(ProcessTaskStub.ScriptReturnType.STDOUT_STRING)
                .requiringExitCodeZero().newTask());
        t.block();
        f.delete();
        t.get();
    }

    @Override
    public void customize() {
        LOG.info(" >> TerraformDockerDriver.customize() ...");
        TerraformDriver.super.customize();
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
