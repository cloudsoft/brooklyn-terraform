package io.cloudsoft.terraform.util;

import org.apache.brooklyn.util.core.predicates.DslPredicates;

public class Constraints {

    public static DslPredicates.DslPredicate<Object> lessThan(Object x) {
        DslPredicates.DslPredicateDefault result = new DslPredicates.DslPredicateDefault();
        result.lessThan = x;
        return result;
    }

}
