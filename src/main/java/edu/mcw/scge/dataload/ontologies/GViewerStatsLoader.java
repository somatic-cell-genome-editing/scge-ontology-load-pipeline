package edu.mcw.scge.dataload.ontologies;


import org.apache.log4j.Logger;

import java.util.*;

/**

 * <p>
 * populates/updates ONT_TERM_STATS table to be used by ontology report pages and GViewer;
 * if filter is NOT NULL, then GViewer XML data is NOT updated for performance reason
 */
public class GViewerStatsLoader {
    private OntologyDAO dao;
    private final Logger logger = Logger.getLogger("gviewer_stats");
    private String version;
    private Set<String> processedOntologyPrefixes;

    // maximum number of annotations that will be used;
    // the rest will be ignored
    private int maxAnnotCountPerTerm;

    private int[] primaryMapKey = new int[4];


    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setMaxAnnotCountPerTerm(int maxAnnotCountPerTerm) {
        this.maxAnnotCountPerTerm = maxAnnotCountPerTerm;
    }

    public int getMaxAnnotCountPerTerm() {
        return maxAnnotCountPerTerm;
    }

    List<String> loadIncomingTermAccs(Map<String,String> ontPrefixes) throws Exception {

        List<String> results = new ArrayList<>();

        String processedOntPrefixes = "";
        String skippedOntPrefixes = "";

        // for every ontology, load list of term acc ids
        for( String ontPrefix: ontPrefixes.values() ) {

            if( getProcessedOntologyPrefixes().contains(ontPrefix) ) {
                List<String> accIds = dao.getAllTermAccIds(ontPrefix);
                results.addAll(accIds);
                logger.debug("GVIEWER STATS: TERM COUNT for " + ontPrefix + " is " + accIds.size());

                if( ontPrefix.equals("*") ) {
                    ontPrefix = "DOID:";
                }
                processedOntPrefixes += ontPrefix + " ";
            } else {
                logger.debug("GVIEWER STATS: TERM COUNT for " + ontPrefix + " is 0 -- not on processed list");

                skippedOntPrefixes += ontPrefix + " ";
            }
        }

        Collections.shuffle(results);
        logger.info("GVIEWER STATS: loaded ontologies, term count="+results.size());
        logger.info("    processed ontology prefixes: ["+processedOntPrefixes.trim()+"]");
        logger.info("    skipped ontology prefixes: ["+skippedOntPrefixes.trim()+"]");

        return results;
    }


    void processXml(TermStats stats, int speciesTypeKey, XmlInfo info) {

        if( info.xmlForTerm!=null ) {
            info.xmlForTerm.append("</genome>\n");
            stats.setXmlForTerm(info.xmlForTerm.toString(), speciesTypeKey);
        }

        if( info.xmlWithChilds!=null ) {
            info.xmlWithChilds.append("</genome>\n");
            stats.setXmlWithChilds(info.xmlWithChilds.toString(), speciesTypeKey);
        }
    }

    String encode(String txt) {
        // utility to encode '<' and '>' in strain symbols
        if( txt.indexOf('<')>=0 ) {
            return txt.replace("<", "&lt;").replace(">", "&gt;");
        }
        return txt;
    }



    public void setProcessedOntologyPrefixes(Set<String> processedOntologyPrefixes) {
        this.processedOntologyPrefixes = processedOntologyPrefixes;
    }

    public Set getProcessedOntologyPrefixes() {
        return processedOntologyPrefixes;
    }

    // structure used to compute xml data for gviewer
    class XmlInfo {
        int featuresForTerm = 0;
        int featuresWithChilds = 0;

        // to filter out duplicate features
        Set<String> features1 = new HashSet<>();
        Set<String> features2 = new HashSet<>();

        public StringBuilder xmlForTerm = null;
        public StringBuilder xmlWithChilds = null;
    }
}
