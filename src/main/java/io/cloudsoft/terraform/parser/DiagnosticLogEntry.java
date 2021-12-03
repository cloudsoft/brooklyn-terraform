package io.cloudsoft.terraform.parser;

public class DiagnosticLogEntry {

    public String severity;
    public String summary;
    public String detail;
    public String address;  // the problematic resource

    public DiagnosticLogEntry() {
    }

    @Override
    public String toString() {
        return "DiagnosticLogEntry {" +
                "\n\t" +"severity=" + severity +
                "\n\t" + "summary=" + summary +
                "\n\t" + "detail=" + detail +
                "\n\t" + "address=" + address +
                '}';
    }
}
