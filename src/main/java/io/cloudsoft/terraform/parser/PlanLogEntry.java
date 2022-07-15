package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class PlanLogEntry {

    public final static String NO_CHANGES = "Plan: 0 to add, 0 to change, 0 to destroy.";

    public enum Provider {
        NOT_SUPPORTED("?"),
        AWS("aws_"),
        VSPHERE("vsphere_"),
        ALIBABA("alicloud_"),
        AZURE("azurerm_"),
        GOOGLE("google_"),
        ORACLE("oci_");

        private String prefix;

        Provider(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    public enum LType {
        @JsonProperty("version") VERSION, // not interested
        @JsonProperty("refresh_start") REFRESH_START,
        @JsonProperty("refresh_restart") REFRESH_RESTART,
        @JsonProperty("refresh_complete") REFRESH_COMPLETE,
        @JsonProperty("apply_start") APPLY_START,
        @JsonProperty("apply_restart") APPLY_RESTART,
        @JsonProperty("apply_complete") APPLY_COMPLETE,
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
    public DiagnosticLogEntry diagnostic;

    public LType type;

    public Provider getProvider(){
       for (Provider p : Provider.values()) {
           if(message.startsWith(p.prefix)) {
               return p;
           }
       }
       return Provider.NOT_SUPPORTED;
    }

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
