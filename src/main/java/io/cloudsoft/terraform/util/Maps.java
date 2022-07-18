package io.cloudsoft.terraform.util;

import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class Maps {
    public Maps() {
    }

    public static <K, V> Map<K, V> newHashMap() {
        return new HashMap();
    }

    public static <K, V> Map<K, V> newLinkedHashMap() {
        return new LinkedHashMap();
    }

    public static <K, V> Map<K, V> newHashMap(Pair<K,V>... parameters) {
        Map<K, V> result = newHashMap();

        for(int i = 0; i < parameters.length; i += 1) {
            result.put(parameters[i].getLeft(), parameters[i].getRight());
        }

        return result;
    }
}
