package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskFactory;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_DOWNLOAD_URL;
import static io.cloudsoft.terraform.TerraformConfiguration.TERRAFORM_PATH;
import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.commandsToDownloadUrlsAsWithMinimumTlsVersion;

public class TerraformSshDriver extends TerraformOnMachineDriver {
    private static final Logger LOG = LoggerFactory.getLogger(TerraformSshDriver.class);

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public ProcessTaskFactory<String> newCommandTaskFactory(boolean withEnvVars, String command) {
        ProcessTaskFactory<String> tf = SshTasks.newSshExecTaskFactory(getMachine(), command).requiringZeroAndReturningStdout();
        if (withEnvVars) tf.environmentVariables(getShellEnvironment());
        return tf;
    }

    @Override
    public void copyTo(InputStream tfStream, String target) {
        getMachine().copyTo(tfStream, target);
    }

    public String getDefaultTerraformExecutable() {
        if (Strings.isBlank(getExpandedInstallDir())) return "terraform";  // don't use path, if expanded dir empty it was taken from path
        // we installed it ourselves
        return Os.mergePathsUnix(getInstallDir(), "terraform");
    }

    // Order of execution during AMP deploy: step 3 - zip up the current configuration files if any, unzip the new configuration files, run `terraform init -input=false`
    @Override
    public void customize() {
        DynamicTasks.queue(Tasks.create("Standard customization - create folders", () -> {
            newScript(CUSTOMIZING).execute();
        }));

        super.customize();
    }

}
