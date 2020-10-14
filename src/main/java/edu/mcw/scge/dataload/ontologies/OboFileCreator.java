package edu.mcw.scge.dataload.ontologies;

import edu.mcw.scge.dao.implementation.OntologyXDAO;
import edu.mcw.scge.datamodel.ontologyx.*;
import edu.mcw.scge.process.FileDownloader;
import edu.mcw.scge.process.Utils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * read data from given ontology and create a obo file
 * Note: If an ontology has synonyms of type 'display_synonym', OBO file header
 *   will contain a line 'synonymtypedef: DISPLAY_SYNONYM ...'
 */
public class OboFileCreator {

    OntologyXDAO dao = new OntologyXDAO();

    // 2013-08-12T12:32:11Z
    static SimpleDateFormat sdtCreationDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private String ontId;
    private String outFileName;
    private String outDir;
    private String version;
    private Map<String,String> versionedFiles;
    private Set<String> emitObsoleteTermsFor;
    private boolean versionStringWritten = false;

    /**
     * dump all terms (with definitions and synonyms) from given ontology into a obo file
     * @param ontId ontology id
     * @throws Exception
     */
    public void run(String ontId) throws Exception {

        long time0 = System.currentTimeMillis();

        if( Utils.isStringEmpty(ontId) ) {
            for( String ontId2: getVersionedFiles().keySet() ) {
                runImpl(ontId2);
            }
        } else {
            runImpl(ontId);
        }

        System.out.println("=== OK ===     elapsed "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    void runImpl(String ontId) throws Exception {

        boolean processObsoleteTerms = getEmitObsoleteTermsFor().contains(ontId);

        // write version string only once
        if( !versionStringWritten ) {
            System.out.println(getVersion());
            versionStringWritten = true;
        }

        setOntId(ontId);

        StringBuffer versionedTerms = new StringBuffer();
        StringBuffer fileTrailer = new StringBuffer();
        StringBuffer outFileName = new StringBuffer();

        String dataVersion = getDataVersion(ontId, versionedTerms, fileTrailer, outFileName);
        StringBuffer oboHeader = initOboHeader(dataVersion);

        StringBuffer subsetDefs = new StringBuffer();
        StringBuffer synonymTypeDefs = new StringBuffer();

        // sort terms by id
        List<Term> terms = processObsoleteTerms ? dao.getAllTerms(ontId) : dao.getActiveTerms(ontId);
        terms.sort(new Comparator<Term>() {
            public int compare(Term o1, Term o2) {
                return o1.getAccId().compareTo(o2.getAccId());
            }
        });

        List<Record> records = new ArrayList<>(terms.size());
        for( Term term: terms ) {
            Record rec = new Record();
            rec.setTerm(term);
            records.add(rec);
        }

        records.parallelStream().forEach( rec -> {

            try {
                for (TermSynonym synonym : dao.getTermSynonyms(rec.getTerm().getAccId())) {
                    // convert synonyms of type 'primary_id' into 'alt_id'
                    if (Utils.stringsAreEqual(synonym.getType(), "primary_id")) {
                        synonym.setType("alt_id");
                    }
                    rec.addSynonym(synonym);
                }

                for (Map.Entry<String, Relation> edge : dao.getTermAncestors(rec.getTerm().getAccId()).entrySet()) {
                    Term parentTerm = dao.getTermByAccId(edge.getKey());
                    rec.addEdge(parentTerm.getAccId() + " ! " + parentTerm.getTerm(), edge.getValue());
                }

                StringBuffer termBuf = new StringBuffer();
                writeTerm(rec, termBuf, synonymTypeDefs, subsetDefs);
                rec.oboText = termBuf.toString();
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        });

        StringBuffer termsBuf = new StringBuffer();
        for( Record rec: records ) {
            termsBuf.append(rec.oboText);
        }

        // see if generated terms are the same as in the versioned file
        if( termsBuf.toString().equals(versionedTerms.toString()) ) {
            System.out.println(getOntId()+": "+terms.size()+" terms the same -- nothing written");
            return;
        }

        Utils.writeStringToFile(termsBuf.toString(), "/tmp/new_"+ontId.toLowerCase()+".obo");
        Utils.writeStringToFile(versionedTerms.toString(), "/tmp/old_"+ontId.toLowerCase()+".obo");

        // generate output file name
        setOutFileName(outFileName.toString());
        System.out.println(getOntId()+" output file: "+getOutFileName());

        // write the header, body and trailer
        updateOboHeaderWithSynonymTypeDefs(oboHeader, subsetDefs, synonymTypeDefs);

        PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(getOutFileName())));
        writer.println(oboHeader);
        writer.print(termsBuf);
        writer.print(fileTrailer);

        writer.close();

        System.out.println("   "+terms.size()+" terms written");
    }

    StringBuffer initOboHeader(String dataVersion) throws Exception {

        StringBuffer oboHeader = new StringBuffer();

        // load ontology header from database
        Ontology ont = dao.getOntology(ontId);
        String header = Utils.defaultString(ont.getOboHeader()).replace("\r\n","\n");
        if( header.isEmpty() ) {
            System.out.println("OBO_HEADER for "+ontId+" ontology is not set in ONTOLOGY table! Generating default header.");
            oboHeader.append("format-version: 1.2.2\n")
                    .append("date: #DATE#\n")
                    .append("auto-generated-by: ").append(getVersion()).append("\n")
                    .append("saved-by: rgd\n")
                    .append("ontology: ").append(ontId.toLowerCase()).append("\n");
        } else {
            oboHeader.append(header);
        }

        // replace #DATE# placeholder with the current date
        int pos = oboHeader.indexOf("#DATE#");
        if( pos>=0 ) {
            oboHeader = oboHeader.replace(pos, pos+6, (new SimpleDateFormat("dd:MM:yyyy HH:mm")).format(new Date()));
        }

        pos = oboHeader.indexOf("#GENERATOR#");
        if( pos>=0 ) {
            oboHeader = oboHeader.replace(pos, pos+11, getVersion());
        }

        pos = oboHeader.indexOf("#DATAVER#");
        if( pos >=0 ) {
            if( dataVersion==null ) {
                oboHeader = oboHeader.replace(pos, pos+9, "1.0");
            } else {
                oboHeader = oboHeader.replace(pos, pos+9, dataVersion);
            }
        }
        return oboHeader;
    }

    String getDataVersion(String ontId, StringBuffer versionedFileTerms, StringBuffer fileTrailer, StringBuffer outFileName) throws Exception {

        String versionedFile = getVersionedFiles().get(ontId);
        if( versionedFile==null ) {
            return null;
        }

        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(versionedFile);
        fd.setLocalFile("data/"+ontId+".obo");
        fd.setPrependDateStamp(true);
        String localFile = fd.downloadNew();

        BufferedReader in = Utils.openReader(localFile);
        String oldDataVersion = "";
        String dataVersion = null;
        String fileDate = "";
        String line;

        // parse header
        while( (line=in.readLine())!=null ) {
            if( Utils.isStringEmpty(line) ) {
                break;
            }
            if( line.startsWith("data-version:") ) {
                // data-version: 7.42
                int dotPos = line.lastIndexOf('.') + 1;
                int minorVer = 1 + Integer.parseInt(line.substring(dotPos));
                int verPos = line.indexOf(": ");
                oldDataVersion = line.substring(verPos+2);
                dataVersion = line.substring(verPos+2, dotPos) + minorVer;
            }
            else if( line.startsWith("date:") ) {
                // date: 05:04:2012 15:52      dd:mm:yyyy
                fileDate = line.substring(12, 12+4)+line.substring(9, 9+2)+line.substring(6, 6+2);
            }
        }

        // load all terms
        while( (line=in.readLine())!=null ) {
            if( line.startsWith("[") && !line.startsWith("[Term]") ) {
                break;
            }
            versionedFileTerms.append(line).append('\n');
        }

        // load file trailer
        if( line!=null )
        do {
            fileTrailer.append(line).append("\n");
            line = in.readLine();
        } while( line!=null );
        in.close();


        // create a backup copy of the versioned file, if it does not exist
        int slashPos = versionedFile.lastIndexOf('/');
        slashPos = versionedFile.lastIndexOf('/', slashPos-1);
        slashPos = versionedFile.lastIndexOf('/', slashPos-1);
        String backupFileName = getOutDir()+versionedFile.substring(slashPos+1);
        outFileName.append(backupFileName);

        // create backup file
        backupFileName = backupFileName.replace(".obo", "_" + fileDate + "_v" + oldDataVersion + ".obo");

        File backupFile = new File(backupFileName);
        if (!backupFile.exists()) {
            String fileContent = Utils.readFileAsString(localFile);
            backupFile.getParentFile().mkdirs();
            Utils.writeStringToFile(fileContent, backupFileName);
        }

        return dataVersion;
    }

    private void writeTerm(Record rec, StringBuffer buf, StringBuffer synonymTypeDefs, StringBuffer subsetDefs) throws Exception {

        buf.append("[Term]\n");
        buf.append("id: ").append(rec.getTerm().getAccId());
        buf.append("\n");
        buf.append("name: ").append(rec.getTerm().getTerm());
        buf.append("\n");

        // export alternate synonyms
        preprocessSynonyms(rec.getSynonyms());

        // 'alt_id' synonyms
        for( TermSynonym synonym: rec.getSynonyms() ) {

            String synonymName = synonym.getName();
            String synonymType = synonym.getType();

            if( synonym.getType().equals("alt_id") ) {
                buf.append(synonymType).append(": ").append(synonymName).append("\n");
            }
        }

        if( rec.getTerm().getDefinition()!=null ) {
            buf.append("def: \"").append(rec.getTerm().getDefinition())
                    .append("\" [").append(getDbXRefs(rec.getTerm().getAccId())).append("]\n");
        }

        if( rec.getTerm().getComment()!=null ) {
            buf.append("comment: ").append(rec.getTerm().getComment());
            buf.append("\n");
        }

        // subsets
        for( TermSynonym synonym: rec.getSynonyms() ) {

            String synonymName = synonym.getName();
            String synonymType = synonym.getType();

            if( synonymType.equals("subset") ) {
                createSubsetDefIfNotExists(synonymName, subsetDefs);
                buf.append("subset: ").append(synonymName).append("\n");
            }
        }

        // export regular synonyms
        for( TermSynonym synonym: rec.getSynonyms() ) {

            String synonymName = synonym.getName();
            String synonymType = synonym.getType();
            String synonymTypeDef = null;

            if( synonymType.contains("synonym") ) {
                String synType = "RELATED";
                switch (synonymType) {
                    case "exact_synonym":
                        synType = "EXACT";
                        break;
                    case "related_synonym":
                        synType = "RELATED";
                        break;
                    case "broad_synonym":
                        synType = "BROAD";
                        break;
                    case "narrow_synonym":
                        synType = "NARROW";
                        break;
                    case "display_synonym":
                        synType = "EXACT";
                        synonymTypeDef = "DISPLAY_SYNONYM";
                        createSynonymTypeDefIfNotExists(synonymTypeDef, "term name abbreviation, used for display", "EXACT", synonymTypeDefs);
                        break;
                }

                String xrefs = Utils.defaultString(synonym.getDbXrefs());

                // 'synonym: "mito" RELATED []'
                buf.append("synonym: \"").append(synonymName).append("\" ").append(synType);
                if (synonymTypeDef != null) { // optional synonym typedef
                    buf.append(" ").append(synonymTypeDef);
                }
                buf.append(" [").append(xrefs).append("]\n");
            }
        }

        // 'xref' synonyms
        for( TermSynonym synonym: rec.getSynonyms() ) {

            String synonymName = synonym.getName();
            String synonymType = synonym.getType();

            if( synonymType.equals("xref") ) {
                buf.append("xref: ").append(synonymName).append("\n");
            }
        }

        // export relationships
        List<String> relationships = preprocessRelationships(rec);
        for( String relationship: relationships ) {
            buf.append(relationship).append("\n");
        }

        if( rec.getTerm().isObsolete() ) {
            buf.append("is_obsolete: true\n");
        }

        // write out created_by and creation_date
        if( rec.getTerm().getCreatedBy()!=null ) {
            buf.append("created_by: ").append(rec.getTerm().getCreatedBy());
            buf.append("\n");
        }
        if( rec.getTerm().getCreationDate()!=null ) {
            // creation_date: '2011-01-04T12:01:33Z'
            buf.append("creation_date: ").append(getCreationDate(rec.getTerm().getCreationDate()));
            buf.append("\n");
        }

        // extra line separating term sections
        buf.append("\n");
    }

    synchronized boolean createSynonymTypeDefIfNotExists(String synTypeDef, String synTypeDefInfo, String synTypeDefType,
                                                         StringBuffer synonymTypeDefs) {
        if( synonymTypeDefs.indexOf(synTypeDef)<0 ) {
            synonymTypeDefs.append("synonymtypedef: ").append(synTypeDef);
            synonymTypeDefs.append(" \"").append(synTypeDefInfo).append("\"");
            if( synTypeDefType!=null ) {
                synonymTypeDefs.append(" ").append(synTypeDefType);
            }
            synonymTypeDefs.append("\n");
            return true;
        }
        return false;
    }

    synchronized boolean createSubsetDefIfNotExists(String subsetDef, StringBuffer subsetDefs) {
        if( subsetDefs.indexOf(subsetDef)<0 ) {
            subsetDefs.append("subsetdef: ").append(subsetDef);
            subsetDefs.append(" \"").append(subsetDef).append("\"");
            subsetDefs.append("\n");
            return true;
        }
        return false;
    }

    // SimpleDateFormat is not thread safe
    synchronized String getCreationDate(Date dt) {
        return sdtCreationDate.format(dt);
    }

    boolean updateOboHeaderWithSynonymTypeDefs(StringBuffer oboHeader, StringBuffer subsetDefs, StringBuffer synonymTypeDefs) throws Exception {

        if( synonymTypeDefs.length()==0 && subsetDefs.length()==0 ) {
            return false;
        }

        // insert synonym type defs BEFORE 'default-namespace:' line
        int pos = oboHeader.indexOf("default-namespace: ");
        if( pos<0 ) {
            throw new Exception("Unexpected OBO header: missing 'default-namespace:' line");
        }
        oboHeader.insert(pos, synonymTypeDefs);
        oboHeader.insert(pos, subsetDefs);
        return true;
    }

    /**
     * properly sort synonyms
     * @param synonyms List of TermSynonym objects
     */
    void preprocessSynonyms(List<TermSynonym> synonyms) {

        // sort synonyms by name, case insensitive, removing whitespace and punctuation
        Collections.sort(synonyms, new Comparator<TermSynonym>() {
            public int compare(TermSynonym o1, TermSynonym o2) {
                return Utils.stringsCompareToIgnoreCase(normalizeName(o1.getName()), normalizeName(o2.getName()));
            }

            String normalizeName(String termName) {
                return termName.replaceAll("[\\s\\-\\,]","");
            }
        });
    }

    List<String> preprocessRelationships(Record rec) {

        List<String> relations = new ArrayList<>();

        // is_a relationships go first
        for( Map.Entry<String, Relation> entry: rec.getEdges().entrySet() ) {
            String rel = entry.getValue().toString().replace("-","_");
            if( rel.equals("is_a") ) {
                relations.add("is_a: "+entry.getKey());
            } else {
                relations.add("relationship: "+rel+" "+entry.getKey());
            }
        }

        Collections.sort(relations);

        return relations;
    }

    private String getDbXRefs(String termAcc) throws Exception {

        List<TermXRef> inRgdXRefs = dao.getTermXRefs(termAcc);
        if( inRgdXRefs==null || inRgdXRefs.isEmpty() )
            return "";

        Collections.sort(inRgdXRefs, new Comparator<TermXRef>() {
            @Override
            public int compare(TermXRef o1, TermXRef o2) {
                return Utils.stringsCompareToIgnoreCase(o1.getXrefValue(), o2.getXrefValue());
            }
        });

        StringBuilder buf = new StringBuilder();
        for( TermXRef xref: inRgdXRefs ) {
            if( xref.getXrefValue()==null ) {
                continue;
            }
            if( buf.length()>0 ) {
                buf.append(", ");
            }

            String value = xref.getXrefValue();

            // format value: escape commas, and 2nd and subsequent colons
            int colon1Pos = value.indexOf(':');
            if( colon1Pos>=0 ) {
                int colon2Pos = value.indexOf(':', colon1Pos+1);
                if( colon2Pos>colon1Pos ) {
                    value = value.substring(0, colon1Pos+1) + value.substring(colon1Pos+1).replace(":", "\\:");
                }
            }
            int commaPos = value.indexOf(',');
            if( commaPos>=0 ) {
                value = value.replace(",", "\\,");
            }
            buf.append(value);

            // export description if any
            if( !Utils.isStringEmpty(xref.getXrefDescription()) ) {
                String desc = xref.getXrefDescription()
                        .replace("\"", "\\\"")
                        .replace(":", "\\:")
                        .replace(",", "\\,");
                buf.append(" \"").append(desc).append("\"");
            }
        }
        return buf.toString();
    }

    public String getOntId() {
        return ontId;
    }

    public void setOntId(String ontId) {
        this.ontId = ontId;
    }

    public String getOutFileName() {
        return outFileName;
    }

    public void setOutFileName(String outFileName) {
        this.outFileName = outFileName;
    }

    public String getOutDir() {
        return outDir;
    }

    public void setOutDir(String outDir) {
        this.outDir = outDir;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setVersionedFiles(Map<String,String> versionedFiles) {
        this.versionedFiles = versionedFiles;
    }

    public Map<String,String> getVersionedFiles() {
        return versionedFiles;
    }

    public void setEmitObsoleteTermsFor(Set<String> emitObsoleteTermsFor) {
        this.emitObsoleteTermsFor = emitObsoleteTermsFor;
    }

    public Set<String> getEmitObsoleteTermsFor() {
        return emitObsoleteTermsFor;
    }
}
