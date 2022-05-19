package io.cloudsoft.terraform;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.cloudsoft.terraform.container.JobBuilder;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.objs.Configurable;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.EntityInitializers;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskBuilder;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.core.task.system.ProcessTaskStub;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.core.task.system.internal.SystemProcessTaskFactory;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang.RandomStringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings({"UnstableApiUsage", "rawtypes"})
public interface TerraformCommons {

    ConfigKey<String> CONTAINER_IMAGE = ConfigKeys.newStringConfigKey("containerImage", "Container image");
    ConfigKey<String> CONTAINER_NAME = ConfigKeys.newStringConfigKey("containerName", "Container name");
    ConfigKey<Boolean> DEV_MODE = ConfigKeys.newBooleanConfigKey("devMode", "When set to true, the namespace" +
            " and associated resources and services are not destroyed after execution.", Boolean.FALSE);
    ConfigKey<String> ACTIVITY_NAME = ConfigKeys.newStringConfigKey("activityName", "Custom name for the activity");
    ConfigKey<List> COMMANDS = ConfigKeys.newConfigKey(List.class,"commands", "SSH command to execute for sensor", Lists.newArrayList());
    String NAMESPACE_CREATE_CMD = "kubectl create namespace amp-%s"; // namespace name
    String NAMESPACE_SET_CMD = "kubectl config set-context --current --namespace=amp-%s"; // namespace name
    String JOBS_CREATE_CMD = "kubectl apply -f %s"; // deployment.yaml absolute path
    String JOBS_FEED_CMD = "kubectl wait --for=condition=complete job/%s"; // containerName
    String JOBS_LOGS_CMD = "kubectl logs jobs/%s"; // containerName
    String NAMESPACE_DELETE_CMD = "kubectl delete namespace amp-%s"; // namespace name

    /**
     * This method converts any brooklyn configuration starting with tf_var. into TERRAFORM environment variables
     */
    static void convertConfigToTerraformEnvVar(Configurable entity) {
        Set<ConfigKey<?>> terraformVars =  entity.config().findKeysPresent(k -> k.getName().startsWith("tf_var"));
        final Map<String,Object> env = MutableMap.copyOf(entity.getConfig(SoftwareProcess.SHELL_ENVIRONMENT));
        terraformVars.forEach(c -> {
            final String bcName = c.getName();
            final String tfName = bcName.replace("tf_var.", "TF_VAR_");
            final Object value = entity.getConfig(ConfigKeys.newConfigKey(Object.class, bcName));
            env.put(tfName, value);
        });
        entity.config().set(SoftwareProcess.SHELL_ENVIRONMENT, ImmutableMap.copyOf(env));
    }

    static String executeAndRetrieveOutput(EntityLocal entity, ConfigBag configBag) {
        List<String> commands =  EntityInitializers.resolve(configBag, COMMANDS);
        String containerImage = EntityInitializers.resolve(configBag, CONTAINER_IMAGE);
        String containerName = EntityInitializers.resolve(configBag, CONTAINER_NAME);
        String activityName = EntityInitializers.resolve(configBag, ACTIVITY_NAME);
        Boolean devMode = EntityInitializers.resolve(configBag, DEV_MODE);

        if((commands == null) || (commands.size() == 0) || Strings.isBlank(containerImage)) {
            throw new IllegalStateException("You must specify command(s) and containerImage when using ContainerSensor");
        }

        containerName = Strings.isBlank(containerName) ?
                (containerImage + RandomStringUtils.random(10, true, true)).toLowerCase():containerName;

        final String jobYamlLocation =  new JobBuilder()
                .withImage(containerImage)
                .withName(containerName)
                .withEnv(EntityInitializers.resolve(configBag, BrooklynConfigKeys.SHELL_ENVIRONMENT))
                .withCommands(Lists.newArrayList(commands))
                .build();

        ProcessTaskWrapper<String> stdoutTask = buildKubeTask(configBag, "Get container output", String.format(JOBS_LOGS_CMD,containerName));

        TaskBuilder taskBuilder = Tasks.builder()
                .displayName(Strings.isBlank(activityName) ? "Container Execution": activityName)
                .add(buildKubeTask(configBag, "Creating Namespace", String.format(NAMESPACE_CREATE_CMD,containerName) , String.format(NAMESPACE_SET_CMD,containerName)))
                .add(buildKubeTask(configBag, "Create Job", String.format(JOBS_CREATE_CMD,jobYamlLocation)))
                .add(buildKubeTask(configBag, "Check Job Feed", String.format(JOBS_FEED_CMD,containerName)))
                .add(stdoutTask);

        if(!devMode) {
            taskBuilder.add(buildKubeTask(configBag, "Delete Namespace", String.format(NAMESPACE_DELETE_CMD,containerName)));
        }
        DynamicTasks.queueIfPossible(taskBuilder.build()).orSubmitAndBlock(entity).andWaitForSuccess();

        return stdoutTask.getStdout();
    }

    static ProcessTaskWrapper<String> buildKubeTask(final ConfigBag configBag, final String taskSummary, final String... kubeCommands) {
        return new SystemProcessTaskFactory.ConcreteSystemProcessTaskFactory<String>( kubeCommands)
                .summary(taskSummary)
                .<String>returning(ProcessTaskStub.ScriptReturnType.STDOUT_STRING)
                .requiringExitCodeZero().configure(configBag.getAllConfig()).newTask();
    }

}
