package io.cloudsoft.terraform.parser;

import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConfigurationParser {

    public static Map<String, String> parseConfigurationResources(File configurationFile){
        Map<String,String> resources = new HashMap<>();
        try {
            File[] directoryListing = configurationFile.listFiles();
            if (directoryListing != null) {
                // we have a dir, loop through found files
                for (File file : directoryListing){
                    // recursively call passing the file
                    Map<String,String> resourcesInFile = parseConfigurationResources(file);
                    for (String key : resourcesInFile.keySet()){
                        if (resources.containsKey(key)){
                            // terraform can't have 2 resources with same address - throw an exception
                            throw new IOException("Terraform configuration error. Multiple resources with same address found: " + key);
                        }
                        resources.put(key, resourcesInFile.get(key));
                    }
                }
            }
            else {
                //check for tf extension
                if (!(FilenameUtils.getExtension(configurationFile.getPath()).equals("tf"))){
                    return resources;
                }
                // read file into string
                String tfFileString = FileUtils.readFileToString(configurationFile);
                // call the parser on the string
                resources =  ConfigurationParser.parseConfigurationResources(tfFileString);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return resources;
    }

    public static Map<String, String> parseConfigurationResources(String configurationString) throws IOException {
        Map<String, String> resourceMap = new HashMap<>();

        // parse resource blocks
        String resourceBlock = "";
        String resourceAddress = "";
        int blockLevel = 0;
        Boolean isInsideResourceBlock = false;

        // NOTE: if you want to remove comments, set the value below to true (false by default)
        Boolean removeComments = false;
        if (removeComments) {
            configurationString = removeComments(configurationString);
        }
        for (String line : StringUtils.split(configurationString, "\n")){
            if (line.replaceFirst("^\\s*", "").startsWith("resource") && (blockLevel == 0)){
                // new resource
                isInsideResourceBlock = true;
                // figure out address
                String[] resourceParams = StringUtils.split(line, "\"");
                resourceAddress = resourceParams[1] + "." + resourceParams[3];
            }
            long addBlockLevels = line.chars().filter(ch -> ch == '{').count();
            long removeBlockLevels = line.chars().filter(ch -> ch == '}').count();
            blockLevel = blockLevel + (int) addBlockLevels - (int) removeBlockLevels;

            if (blockLevel < 0) {
                // more closing than opening brackets - must be a configuration error
                throw new IOException("Terraform configuration error. Block parsing issue - Extra closing bracket found.");
            }
            else if (isInsideResourceBlock) {
                if (blockLevel == 0){
                    // end of resource block
                    resourceBlock += line.substring(0,line.indexOf('}')+1);
                    resourceMap.put(resourceAddress,resourceBlock);

                    // reset values
                    resourceAddress = "";
                    resourceBlock = "";
                }
                else {
                    // middle (or beginning) of resource block, add whole line
                    resourceBlock += line + "\n";
                }
            }

        }

        if (blockLevel != 0){
            // we reached the end of the file but must be incorrect, because we are not at correct block level
            throw new IOException("Terraform configuration error. Block parsing issue - block not closed at end of file.");
        }
        return resourceMap;
    }

    public static String removeComments(String configurationString) {
        ArrayList<String> configLinesWithoutComments = new ArrayList<>();
        AtomicBoolean isInsideMultilineComment = new AtomicBoolean(false);
        Arrays.asList(StringUtils.split(configurationString, "\n")).forEach(line -> {
            if (!(line.replaceFirst("^\\s*", "").startsWith("#") || line.replaceFirst("^\\s*", "").startsWith("//"))){
                // not a single line comment, check if multiple line comment else add to String
                if (line.contains("/*")) {
                    // start of a multiline comment
                    isInsideMultilineComment.set(true);

                    String lineWithoutComment = line.substring(0,line.indexOf("/*"));
                    if (!Strings.isBlank(lineWithoutComment.replaceFirst("^\\s*", ""))){
                        configLinesWithoutComments.add(lineWithoutComment);
                    }
                }
                else if (isInsideMultilineComment.get() && (!line.contains("*/"))) {
                    // middle of multiline comment, do nothing
                }
                else if (isInsideMultilineComment.get() && (line.contains("*/"))) {
                    // end of multiline comment,
                    isInsideMultilineComment.set(false);

                    String lineWithoutComment = line.substring(line.indexOf("*/")+2);
                    if (!Strings.isBlank(lineWithoutComment.replaceFirst("^\\s*", ""))){
                        configLinesWithoutComments.add(lineWithoutComment);
                    }
                }
                else {
                    // not a comment, add
                    configLinesWithoutComments.add(line);
                }
            }
        });
        return String.join("\n", configLinesWithoutComments);
    }
}
