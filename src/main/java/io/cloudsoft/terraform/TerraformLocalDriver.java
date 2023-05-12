package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskFactory;
import org.apache.brooklyn.util.core.task.system.internal.SystemProcessTaskFactory;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

public class TerraformLocalDriver extends TerraformOnMachineDriver implements TerraformDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformLocalDriver.class);

    public TerraformLocalDriver(EntityLocal entity) {
        super(entity, null);
    }

    protected TerraformConfigurationImpl entity() { return (TerraformConfigurationImpl) Entities.deproxy(getEntity()); }

    @Override
    public ProcessTaskFactory<String> newCommandTaskFactory(boolean withEnvVars, String command) {
        ProcessTaskFactory<String> tf = new SystemProcessTaskFactory.ConcreteSystemProcessTaskFactory<>(command)
                    .requiringZeroAndReturningStdout();
        if (withEnvVars) tf.environmentVariables(getShellEnvironment());
        return tf;
    }

    @Override
    public void copyTo(InputStream tfStream, String target) {
        try {
            File tf = new File(target);

            File tfp = tf.getParentFile();
            tfp.mkdirs();

            Files.copy(tfStream, tf.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Exceptions.propagateAnnotated("Cannot create file: "+target, e);
        }
    }

    public String getDefaultTerraformExecutable() {
        // path
        return "terraform";
    }

    @Override
    public void install() {
        // for local driver we have to correctly set install and run dirs
        // set on box
        fixRunDir();

        //not used, but for good measure avoid bogus install dirs
        setInstallDir(getRunDir());
        setExpandedInstallDir(getRunDir());

        super.install();

        DynamicTasks.queue(newCommandTaskFactory(false, "mkdir -p "+getTerraformActiveDir()).newTask());
        DynamicTasks.waitForLast();
    }

    @Override
    public String getRunDir() {
        fixRunDir();
        return super.getRunDir();
    }

    protected void fixRunDir() {
        String base = getEntity().getConfig(BrooklynConfigKeys.ONBOX_BASE_DIR);
        if (base==null) {
            setRunDir(null);
            getEntity().config().set(BrooklynConfigKeys.ONBOX_BASE_DIR,
                    "/tmp/brooklyn-"+ Os.user()   // what LocalhostMachineLocaiton uses
                    // other options:
//                    "/tmp/brooklyn/managed-terraform-deployments"
//                    "~/brooklyn-terraform"
            );
            getRunDir();
        }
    }

    protected void downloadTerraform() {
        throw new IllegalStateException("Terraform not permitted to downloaded to local machine. " +
                "It must be available either" +
                " in the location specified in key '"+TerraformConfiguration.TERRAFORM_PATH.getName()+"'" +
                " or on the local path" +
                " to use execution mode '"+TerraformCommons.LOCAL_MODE+"'");
    }

    @Override
    public int execute(Map flags, List<String> script, String summaryForLogging) {
        // we should have removed all paths that call to this, but if not, log
        LOG.warn("SKIPPING execution for terraform local of '"+summaryForLogging+"':\n"+Strings.join(script, "\n"));
        return 0;
    }


    // copied from TerraformContainerDriver
    @Override
    public void postLaunch() {
        lifecyclePostStartCustom();
    }
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


}
