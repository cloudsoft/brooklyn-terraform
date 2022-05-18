package io.cloudsoft.terraform;

import com.google.common.collect.Lists;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;

import java.util.List;

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
}
