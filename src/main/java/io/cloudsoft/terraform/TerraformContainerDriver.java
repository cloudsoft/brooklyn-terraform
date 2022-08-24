package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.tasks.kubectl.ContainerCommons;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskFactory;
import org.apache.brooklyn.tasks.kubectl.ContainerTaskResult;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskTags;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskStub;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.core.task.system.internal.SystemProcessTaskFactory;
import org.apache.brooklyn.util.exceptions.Exceptions;
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
 *
 *
 *  NOTE: to debug, you can use the following (or launch a container with the tfws mounted)
 *
 *  Entities.submit(getEntity(), newCommandTaskFactory(false, "ls -al")).asTask().get()
 */
public class TerraformContainerDriver implements TerraformDriver {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformContainerDriver.class);

    protected final EntityLocal entity;

    public TerraformContainerDriver(EntityLocal entity) {
        this.entity = entity;
    }

    @Override
    public ContainerTaskFactory<?,String> newCommandTaskFactory(boolean withEnvVars, String command) {
        MutableMap<Object, Object> config = MutableMap.of()
                .add(getEntity().getConfig(TerraformCommons.KUBEJOB_CONFIG))
                .add(ConfigBag.newInstance().configure(ContainerCommons.WORKING_DIR, getTerraformActiveDir()).getAllConfig());

        // if image specified, use that
        String image = getEntity().getConfig(TerraformConfiguration.CONTAINER_IMAGE);
        if (Strings.isNonBlank(image)) config.put("image", image);

        String namespace = "cloudsoft-"+getEntity().getApplicationId()+"-"+getEntity().getId()+"-terraform";
        LOG.debug("Launching task in namespace "+namespace+" with config "+config+" for command "+command);
        ContainerTaskFactory<?,String> tf = ContainerTaskFactory.newInstance()
                .bashScriptCommands(command)
                .allowingNonZeroExitCode(false)
                .useNamespace(namespace, null, false)
                .configure(config)
                .returningStdout();
        if (withEnvVars) tf.environmentVariables(getShellEnvironment());
        return tf;
    }

    transient String cachedHomeDir = null;
    @Override
    public String computeHomeDir(boolean clearCache) {
        if (clearCache || cachedHomeDir==null) {
            cachedHomeDir = DynamicTasks.queue(newCommandTaskFactory(false, "cd ~ && pwd").returningStdout()).getUnchecked().trim();
        }
        return cachedHomeDir;
    }

    @Override
    public String makeTerraformCommand(String argument) {
        return "terraform "+argument;
    }

    @Override
    public String getTerraformActiveDir() {
        // volume mount is not unique to entity but directory is
        Map<String, Object> kubecfg = getEntity().getConfig(TerraformCommons.KUBEJOB_CONFIG);
        String baseDir = null;
        if (kubecfg!=null) baseDir = Strings.toString(kubecfg.get(ContainerCommons.WORKING_DIR.getName()));
        if (baseDir==null) baseDir = ".";
        baseDir = Strings.removeAllFromEnd(baseDir, "/", "\\") + "/";
        baseDir += "brooklyn-terraform/"+getEntity().getApplicationId()+"/"+getEntity().getId()+"/active/";
        return baseDir;
    }

    @Override
    public void copyTo(InputStream tfStream, String target) {
        File f = Os.writeToTempFile(tfStream, "terraform-" + getEntity().getId(), "dat");
        ContainerTaskFactory<?, String> cf = newCommandTaskFactory(false, "sleep 120");
        TaskAdaptable<String> tc = Entities.submit(getEntity(), cf
                .summary("sleeping container to allow files to be copied").newTask());
        ContainerTaskResult ctr = (ContainerTaskResult) TaskTags.getTagsFast(tc.asTask()).stream().filter(x -> x instanceof ContainerTaskResult).findAny().orElseThrow(() -> new IllegalStateException("Cannot find namespace result on task " + tc));

        synchronized (ctr) {
            while (!ctr.getContainerStarted() && !tc.asTask().isDone()) {
                try {
                    ctr.wait(100);
                } catch (InterruptedException e) {
                    throw Exceptions.propagate(e);
                }
            }
        }
        if (tc.asTask().isDone()) throw new IllegalStateException("Container for file upload failed prematurely, when copying to "+target);

        String namespace = ctr.getNamespace();
        String pod = ctr.getKubePodName();
        if (Strings.isBlank(namespace) || Strings.isBlank(pod)) throw new IllegalStateException("Unable to get pod name from task when copying to "+target);

        // https://medium.com/@nnilesh7756/copy-directories-and-files-to-and-from-kubernetes-container-pod-19612fa74660
        ProcessTaskWrapper<Object> t = DynamicTasks.queue(new SystemProcessTaskFactory.ConcreteSystemProcessTaskFactory<String>(
                "kubectl cp " + f.getAbsolutePath() + " " + namespace + "/" + pod + ":" + target)
                .summary("Copying data to " + target)
                .returning(ProcessTaskStub.ScriptReturnType.STDOUT_STRING)
                .requiringExitCodeZero().newTask());
        t.block();
        f.delete();
        ContainerTaskResult result = (ContainerTaskResult) TaskTags.getTagsFast(tc.asTask()).stream().filter(x -> x instanceof ContainerTaskResult).findAny().orElse(null);
        if (result!=null && result.getKubeJobName()!=null) {
            // deleting a job terminates the containers, but sometimes (eg Docker Desktop) this is not immediate, and can take 20s (!)
            Entities.submit(getEntity(), cf.newDeleteJobTask(result.getKubeJobName()).allowingNonZeroExitCode().summary("cancel sleeping container used for file copy"));
        }
        t.get();
    }

    @Override
    public void customize() {
        LOG.trace(" >> TerraformDockerDriver.customize() ...");
        TerraformDriver.super.customize();
    }

    @Override
    public void launch() { TerraformDriver.super.launch(); }

    @Override
    public void postLaunch() {
        lifecyclePostStartCustom();
    }

    private TerraformConfigurationImpl entity() { return (TerraformConfigurationImpl) Entities.deproxy(getEntity()); }

    //@Override
    protected void lifecyclePostStartCustom() {
        // we don't need anything except connecting sensors (others come from SoftwareProcessLifecycle)

//        entity().postDriverStart();
//        if (entity().connectedSensors) {
//            // many impls aren't idempotent - though they should be!
//            log.debug("skipping connecting sensors for "+entity()+" in driver-tasks postStartCustom because already connected (e.g. restarting)");
//        } else {
//            log.debug("connecting sensors for "+entity()+" in driver-tasks postStartCustom because already connected (e.g. restarting)");
            entity().connectSensors();
//        }
//        entity().waitForServiceUp();
//        entity().postStart();
//        super.postStartCustom(parameters);
    }

    @Override
    public Location getLocation() {
        // can we make this not get invoked?
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
        LOG.trace(" >> TerraformDockerDriver.start() ...");
        customize();
        launch();
        postLaunch();
    }

    @Override
    public void restart() {
        LOG.trace(" >> TerraformDockerDriver.restart() ... just doing apply");
        ((TerraformConfiguration)getEntity()).apply();
    }

    @Override public void stop() { TerraformDriver.super.stop(); }

    @Override
    public void kill() {
        LOG.debug(" >> TerraformDockerDriver.kill() ... same as stop");
        stop();
    }

    @Override
    public void deleteFilesOnDestroy() {
        runQueued(newCommandTaskFactory(false, "cd .. && rm -rf active backup").deleteNamespace(true));
    }

}
