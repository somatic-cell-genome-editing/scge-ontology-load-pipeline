package edu.mcw.scge.dataload.ontologies;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * Obo files that failed to pass 'well-formed obo file' test
 */
public class MalformedOboFiles {
    private static MalformedOboFiles ourInstance = new MalformedOboFiles();
    private Set<String> malformedOntIds = new HashSet<String>();

    public static MalformedOboFiles getInstance() {
        return ourInstance;
    }

    private MalformedOboFiles() {
    }

    public void addOntId(String ontId) {
        malformedOntIds.add(ontId);
    }

    public boolean isWellFormed(String ontId) {
        return !malformedOntIds.contains(ontId);
    }
}
