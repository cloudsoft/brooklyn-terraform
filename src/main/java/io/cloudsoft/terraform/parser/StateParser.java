package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.cloudsoft.terraform.TerraformConfiguration;
import io.cloudsoft.terraform.TerraformConfiguration.TerraformStatus;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.cloudsoft.terraform.TerraformDriver.*;
import static io.cloudsoft.terraform.parser.PlanLogEntry.NO_CHANGES;
import static io.cloudsoft.terraform.parser.PlanLogEntry.Provider.GOOGLE;

/**
 * Naive version. To be improved further.
 */
public final class StateParser {
    private static final Logger LOG = LoggerFactory.getLogger(StateParser.class);
    public static final ImmutableList BLANK_ITEMS = ImmutableList.of("[]", "", "null", "\"\"", "{}", "[{}]");

    private static  Predicate<PlanLogEntry> providerPredicate = ple -> ple.getProvider() != PlanLogEntry.Provider.NOT_SUPPORTED;
    private static  Predicate<PlanLogEntry> changeSummaryPredicate = ple -> ple.type == PlanLogEntry.LType.CHANGE_SUMMARY;
    private static  Predicate<PlanLogEntry> outputsPredicate = ple -> ple.type == PlanLogEntry.LType.OUTPUTS;
    private static  Predicate<PlanLogEntry> plannedChangedPredicate = ple -> ple.type == PlanLogEntry.LType.PLANNED_CHANGE;
    private static  Predicate<PlanLogEntry> driftPredicate = ple -> ple.type == PlanLogEntry.LType.RESOURCE_DRIFT;
    private static  Predicate<PlanLogEntry> errorPredicate = ple -> ple.type == PlanLogEntry.LType.DIAGNOSTIC;
    private static Predicate<JsonNode> isNotBlankPredicate = node -> node != null && !BLANK_ITEMS.contains((node instanceof TextNode) ? node.asText() : node.toString());


    public static Map<String, Map<String,Object>> parseResources(final String state){
        Map<String, Map<String,Object>> result  = MutableMap.of();
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode root = objectMapper.readTree(state);

            if(root.isEmpty() || !root.isContainerNode()) {
                throw new  IllegalArgumentException ("This is not a valid TF state!");
            }
            if (root.get("terraform_version") == null) {
                // probably no data
                return result;
            }

            if(!root.has("values")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a values node!");
            }
            if(!root.get("values").has("root_module")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a root_module node!");
            }

            JsonNode resourceNode = root.at("/values/root_module/resources");
            resourceNode.forEach(resource ->  {
                Map<String, Object>  resourceBody = new LinkedHashMap<>();

                //if (resource.has("mode") && "managed".equals(resource.get("mode").asText())) {
                    result.put(resource.get("address").asText(), resourceBody);

                    resourceBody.put("resource.address", resource.get("address").asText());
                    resourceBody.put("resource.mode", resource.get("mode").asText());
                    resourceBody.put("resource.type", resource.get("type").asText());
                    resourceBody.put("resource.name", resource.get("name").asText());
                    resourceBody.put("resource.provider", resource.get("provider_name").asText());
                    if(resource.has("values")) {
                        Iterator<Map.Entry<String, JsonNode>>  it = resource.get("values").fields();
                        while(it.hasNext()) {
                            Map.Entry<String,JsonNode> value =  it.next();
                            if(isNotBlankPredicate.test(value.getValue())) {
                                if((resourceBody.get("resource.address").toString().startsWith(GOOGLE.getPrefix()) && value.getKey().equals("cluster_config"))){
                                    parseClusterData(value.getValue(), "value.cluster_config", resourceBody);
                                } else {
                                    resourceBody.put("value." + value.getKey(), value.getValue() instanceof TextNode? value.getValue().asText() : value.getValue().toString());
                                }
                            }
                        }
                    }

                    if(resource.has("sensitive_values")) {
                        Iterator<Map.Entry<String, JsonNode>>  it = resource.get("sensitive_values").fields();
                        while(it.hasNext()) {
                            Map.Entry<String,JsonNode> value =  it.next();
                            if(isNotBlankPredicate.test(value.getValue())) {
                                resourceBody.put("sensitive.value." + value.getKey(),  value.getValue() instanceof TextNode? value.getValue().asText() : value.getValue().toString());
                            }
                        }
                    }
                //}

            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse Terraform state!", e);
        }
        return result;
    }

    /**
     * We need to process this node to extract useful cluster data.
     * Careful with this one, because it is a tad recursive!
     * @param value
     * @return
     */
    private static void parseClusterData(JsonNode value, String prefix, Map<String,Object> result) {
        if (value instanceof ArrayNode ) {
            ArrayNode arrayNode = (ArrayNode) value;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode node = arrayNode.get(i);
                if (node instanceof TextNode) {
                    result.put(prefix +".[" + i + "]", node.asText());
                } else {
                    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> pair = it.next();
                        if (isNotBlankPredicate.test(pair.getValue())) {
                            switch (pair.getKey()) {
                                case "master_config":
                                    parseClusterData(pair.getValue(), prefix + "[" + i + "].master_config", result);
                                    break;
                                case "worker_config":
                                    parseClusterData(pair.getValue(), prefix + "[" + i + "].worker_config", result);
                                    break;
                                case "instance_names":
                                    parseClusterData(pair.getValue(), prefix + "[" + i + "].instance_name", result);
                                    break;
                                default:
                                    if (pair.getValue() instanceof TextNode) {
                                        result.put(prefix + "[" + i + "]." + pair.getKey(), pair.getValue().asText());
                                    }
                            }
                        }
                    }
                }
            }
        }
    }

    public static Map<String, Object> parsePlanLogEntries(Entity entity, final String planLogEntriesAsStr) {
        if (entity.config().get(TerraformConfiguration.TERRAFORM_CLOUD_MODE)) {
            return MutableMap.of(PLAN_MESSAGE, "Plan output unavailable because using cloud."
                    // always assume it is SYNC for cloud, otherwise we get errors
                    , PLAN_STATUS, TerraformStatus.SYNC
            );
        }
        return parsePlanLogEntries(planLogEntriesAsStr, entity.config().get(TerraformConfiguration.TERRAFORM_RESOURCES_IGNORED_FOR_DRIFT));
    }

    public static Map<String, Object> parsePlanLogEntriesForTest(final String planLogEntriesAsStr) {
        return parsePlanLogEntries(planLogEntriesAsStr, null);
    }

    public static Map<String, Object> parsePlanLogEntries(final String planLogEntriesAsStr, Collection<String> resourcesToIgnoreForDrift) {
        String[] planLogEntries = planLogEntriesAsStr.split(System.lineSeparator());
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        List<PlanLogEntry> planLogs = Arrays.stream(planLogEntries).map(log -> {
            try {
                if (!log.trim().startsWith("{")) {
                    // in some cases, including with terraform cloud, non-json lines are included
                    return null;
                } else {
                    return objectMapper.readValue(log, PlanLogEntry.class);
                }
            } catch (JsonProcessingException e) {
                LOG.warn("Unable to parse plan log entry: "+log, e);
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();

        planLogs.stream().filter(providerPredicate).findFirst().ifPresent(p -> result.put(PLAN_PROVIDER, p.getProvider()));
        Set<PlanLogEntry.Provider> providers = planLogs.stream().filter(providerPredicate).map(p -> p.getProvider()).collect(Collectors.toSet());
        if (!providers.isEmpty()) {
            result.put(PLAN_PROVIDER, providers.iterator().next());
            // currently only support some providers
            result.put(PLAN_PROVIDERS, providers);
        }

        Optional<PlanLogEntry> changeSummaryLog = planLogs.stream().filter(changeSummaryPredicate).findFirst(); // it is not there when the config is broken
        String planMessage = null;

        planLogs.stream().filter(outputsPredicate).findFirst().ifPresent(ple -> {
            List<Map<String,Object>> outputs = new ArrayList<>();
            ple.outputs.forEach((oK, oV) -> {
                if (!"noop".equals(oV.get("action"))) {
                    outputs.add(ImmutableMap.of(
                            "output.addr", oK,
                            "output.action", oV.get("action").toString()
                    ));
                }
            });
            if(!outputs.isEmpty()) {
                result.put("tf.output.changes", outputs);
            }
        });

        /*
         * See comments at RefreshTerraformModelAndSensors.
         * This needs to return the set of "resources_changed_state_only".
         * It should also distinguish between "resources_changed_drifted" and "resources_changed_plan".
         */
        if (planLogs.stream().anyMatch(plannedChangedPredicate.or(driftPredicate))) {

            List<Map<String,Object>> resources = MutableList.of();
            Map<String,Object> resourcesChangesPlanned = MutableMap.of();
            Map<String,Object> resourcesDriftDetected = MutableMap.of();
            Set<String> resourcesDriftDetectedStateOnly = MutableSet.of();
            Set<String> resourcesDriftDetectedChangesNeeded = MutableSet.of();

            planLogs.stream().filter(plannedChangedPredicate).forEach(ple -> {
                if (!"noop".equals(ple.change.get("action"))) {
                    String addr = ((Map<String, String>) ple.change.get("resource")).get("addr");
                    resources.add(ImmutableMap.of(
                            "resource.addr", addr,
                            "resource.change_type", Strings.toString(ple.type),
                            "resource.action", Strings.toString(ple.change!=null ? ple.change.get("action") : null)
                    ));
                    resourcesChangesPlanned.put(addr, ple.change);
                }
            });
            planLogs.stream().filter(driftPredicate).forEach(ple -> {
                if (!"noop".equals(ple.change.get("action"))) {
                    LOG.debug("Detected drift: "+ple);
                    String addr = ((Map<String, String>) ple.change.get("resource")).get("addr");
                    resources.add(ImmutableMap.of(
                            "resource.addr", addr,
                            "resource.change_type", Strings.toString(ple.type),
                            "resource.action", Strings.toString(ple.change!=null ? ple.change.get("action") : null)
                    ));
                    resourcesDriftDetected.put(addr, ple.change);
                }
            });

            if (resourcesToIgnoreForDrift!=null) resourcesToIgnoreForDrift.forEach(addr -> {
                if (resourcesDriftDetected.containsKey(addr)) {
                    LOG.debug("Ignoring drift detected at known phantom drifter: "+addr);
                    resourcesDriftDetected.remove(addr);
                    resourcesChangesPlanned.remove(addr);
                }
            });

            resourcesDriftDetected.forEach((addr,change) -> {
                if (resourcesChangesPlanned.containsKey(addr)) {
                    resourcesDriftDetectedChangesNeeded.add(addr);
                } else {
                    resourcesDriftDetectedStateOnly.add(addr);
                }
            });

            if (!resources.isEmpty()) {
                result.put(RESOURCE_CHANGES, resources);
                result.put(RESOURCES_CHANGES_PLANNED, resourcesChangesPlanned);
                result.put(RESOURCES_DRIFT_DETECTED, resourcesDriftDetected);
                result.put(RESOURCES_DRIFT_DETECTED_CHANGES_NEEDED, resourcesDriftDetectedChangesNeeded);
                result.put(RESOURCES_DRIFT_DETECTED_STATE_ONLY, resourcesDriftDetectedStateOnly);

                if (resourcesChangesPlanned.isEmpty()) {
                    // all resources are state only
                    planMessage = "Drift detected in state. No changes required to resources but local state needs an update.";
                    result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.STATE_CHANGE);
                } else {
                    result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.DRIFT);
                    if (!resourcesDriftDetectedChangesNeeded.isEmpty()) {
                        planMessage = "Drift detected. Infrastructure state has changed in a way that does not match the configuration.";
                    } else {
                        // either the plan has changed, or drift was detected but local state has been fully refreshed since then
                        planMessage = "Current plan does not match infrastructure.";
                    }
                    planMessage += " Run apply to align infrastructure and configuration. " +
                            "Configurations made outside terraform will be lost if not added to the configuration. " + changeSummaryLog.get().message;
                }
            }
        }

        if (planLogs.stream().anyMatch(errorPredicate)) {
            List<Map<String,Object>> resources = new ArrayList<>();
            result.put(PLAN_MESSAGE, "Something went wrong. Check your configuration.");
            StringBuilder sb = new StringBuilder();
            planLogs.stream().filter(ple -> ple.type == PlanLogEntry.LType.DIAGNOSTIC).forEach(ple -> {
                if(StringUtils.isNotBlank(ple.diagnostic.address)) {
                    resources.add(ImmutableMap.of(
                            "resource.addr", ple.diagnostic.address,
                            "resource.action", "No action. Unrecoverable state."
                    ));
                }
                if(ple.diagnostic.detail != null) {
                    sb.append(ple.message + ple.diagnostic.detail).append("\n");
                }
                sb.append(ple.message).append("\n");
            });
            result.put("tf.errors",  sb);
            result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.ERROR);
            if(!resources.isEmpty()) {
                result.put(RESOURCE_CHANGES, resources);
                result.put(PLAN_MESSAGE, "Terraform in UNRECOVERABLE error state.");
            } else {
                result.put(PLAN_MESSAGE, "Terraform in RECOVERABLE error state. Check configuration syntax.");
            }
            return result;
        }

        if (result.containsKey("tf.output.changes")) {
            // output vars added/removed, or value updated
            if (planMessage==null) planMessage = "Outputs have changed. " + changeSummaryLog.get().message;
            else planMessage = "Outputs have changed. Additionally: " + planMessage;
            if (result.get(PLAN_STATUS)==null) result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.STATE_CHANGE);
        }

        if (planMessage!=null) {
            // something above ran, and set the status
            result.put(PLAN_MESSAGE, planMessage);

        } else {
            // no changes, or unknown format

            if (changeSummaryLog.isPresent()) {
                boolean noChangesDetected = false;
                if (changeSummaryLog.get().changes != null &&
                        changeSummaryLog.get().changes.get("add").equals(0) &&
                        changeSummaryLog.get().changes.get("change").equals(0) &&
                        changeSummaryLog.get().changes.get("remove").equals(0)) {
                    noChangesDetected = true;
                } else if (NO_CHANGES.equals(changeSummaryLog.get().message)) {
                    noChangesDetected = true;
                }

                if (noChangesDetected) {
                    result.put(PLAN_MESSAGE, "No changes. Your infrastructure matches the configuration.");
                    result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.SYNC);
                } else {
                    // unexpected; we should have gotten drift or planned change objects and already have a planMessage, so not come here!
                    result.put(PLAN_MESSAGE, "Configuration and infrastructure do not match. " + changeSummaryLog.get().message);
                    result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.DRIFT);
                }
            } else {
                // also shouldn't come here
                result.put(PLAN_MESSAGE, "Unexpected information returned by plan.");
                result.put(PLAN_STATUS, TerraformConfiguration.TerraformStatus.ERROR);
            }
        }

        return result;
    }

}
