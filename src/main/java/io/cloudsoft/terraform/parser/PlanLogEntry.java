package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class PlanLogEntry {

    public final static String NO_CHANGES = "Plan: 0 to add, 0 to change, 0 to destroy.";

    public enum LType {
        @JsonProperty("version") VERSION, // not interesred
        @JsonProperty("refresh_start") REFRESH_START,
        @JsonProperty("refresh_restart") REFRESH_RESTART,
        @JsonProperty("refresh_complete") REFRESH_COMPLETE,
        @JsonProperty("change_summary") CHANGE_SUMMARY,
        @JsonProperty("planned_change") PLANNED_CHANGE, // interested in this - create /update
        @JsonProperty("resource_drift") RESOURCE_DRIFT, // interested in this
        @JsonProperty("outputs") OUTPUTS,
        @JsonProperty("diagnostic") DIAGNOSTIC // configuration problems
    }

    public PlanLogEntry() {
    }

    // interested in 'Drift detected (update)'
    // interested in 'Plan to update'
    @JsonProperty(value = "@message")
    public String message;

    // interested in changes: x, where x > 0 for type = CHANGE_SUMMARY
    public Map<String, Object> changes;

    // interested in change: x, where x > 0 for type = PLANNED_CHANGE and type = RESOURCE_DRIFT
    public Map<String, Object> change;

    public Map<String, Map<String,Object>> outputs;

    // interested in config error for type = DIAGNOSTIC
    public Map<String,Object> diagnostic;

    public LType type;

    @Override
    public String toString() {
        return "PlanLogEntry{" +
                "\n\t" +"type=" + type +
                "\n\t" + "message=" + message +
                "\n\t" + "changes=" + changes +
                "\n\t" + "changes=" + change +
                "\n\t" + "outputs=" + outputs +
                '}';
    }

}
