package edu.mcw.scge.dataload.ontologies;

import edu.mcw.scge.dao.implementation.*;
import edu.mcw.scge.dao.spring.StringListQuery;
import edu.mcw.scge.dao.spring.StringMapQuery;
import edu.mcw.scge.datamodel.ontologyx.*;
import edu.mcw.rgd.pipelines.PipelineSession;
import edu.mcw.scge.process.Utils;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * wrapper class to manage all interactions with database
 */
public class OntologyDAO {
    OntologyXDAO dao = new OntologyXDAO();


    protected final Logger logErrors = Logger.getLogger("errors");
    protected final Logger logInsertedTerms = Logger.getLogger("insertedTerms");
    protected final Logger logInsertedXRefs = Logger.getLogger("insertedXRefs");
    protected final Logger logDeletedXRefs = Logger.getLogger("deletedXRefs");
    protected final Logger logDescChangedXRefs = Logger.getLogger("descChangedXRefs");
    protected final Logger logInsertedDags = Logger.getLogger("insertedDags");
    protected final Logger logDeletedDags = Logger.getLogger("deletedDags");

    private Set<String> ontologiesWithSuppressedTermObsoletion;


    /**
     * get ontology object given ont_id
     * @param ontId ontology id
     * @return Ontology object or null if ont_id is invalid
     * @throws Exception if something wrong happens in spring framework
     */
    public Ontology getOntology(String ontId) throws Exception {
        return dao.getOntology(ontId);
    }

    /**
     * get an ontology term given term accession id
     * @param termAcc term accession id
     * @return OntTerm object if given term found in database or null otherwise
     * @throws Exception if something wrong happens in spring framework
     */
    public Term getTerm(String termAcc) throws Exception {

        return termAcc==null ? null : dao.getTermWithStatsCached(termAcc);
    }

    public Term getRdoTermByTermName(String term) throws Exception {

        // original query ic case sensitive
        //return dao.getTermByTermName(term, "RDO");

        String sql = "SELECT * FROM ont_terms WHERE LOWER(term)=LOWER(?) AND ont_id=?";
        List terms = dao.executeTermQuery(sql, new Object[]{term, "RDO"});
        return terms.isEmpty() ? null : (Term)terms.get(0);
    }

    /**
     * insert ontology term into database if it does not exist
     * @param term OntTerm object to be inserted
     * @param session PipelineSession object
     * @throws Exception if something wrong happens in spring framework
     */
    public void insertTerm(Term term, PipelineSession session) throws Exception {

        // extra check: ids parentTermAcc normalized
        fixTermNameDefComment(term);
        int r = dao.insertTerm(term);
        if( r!=0 ) {
            logInsertedTerms.info("INSERT|"+term.dump("|"));
//            session.incrementCounter("TERMS_INSERTED_"+term.getOntologyId(), 1);
        }
    }

    /**
     * update ontology term in the database
     * @param term OntTerm object to be inserted
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int updateTerm(Term term) throws Exception {

        fixTermNameDefComment(term);
        return dao.updateTerm(term);
    }

    private void fixTermName(Term term) {
        // sometimes terms with null term names are found in database (due to a logic flaw in the pipeline?)
        // so we fix that here: if term name is not given, it will be set to a single space
        String termName = term.getTerm();
        if( termName==null || termName.trim().isEmpty() ) {
            term.setTerm(" ");

            // log all occurrences of fixed term names to error log
            logErrors.warn("term "+term.getAccId()+" has no term name");
        }

        if(Utils.isStringEmpty(term.getAccId()) ) {
            logErrors.warn("term "+termName+" has no term acc!!!");
        }
    }

    private void fixTermNameDefComment(Term term) {
        fixTermName(term);

        // fix term definition: replace any tabs and new lines with spaces
        String txt = term.getDefinition();
        if( txt!=null ) {
            if( txt.contains("\t") ) {
                txt = txt.replace('\t', ' ');
                term.setDefinition(txt);
            }
            if( txt.contains("\n") ) {
                txt = txt.replace('\n', ' ');
                term.setDefinition(txt);
            }
            if( txt.contains("\r") ) {
                txt = txt.replace('\r', ' ');
                term.setDefinition(txt);
            }
        }

        // fix term comment: replace any tabs and new lines with spaces
        txt = term.getComment();
        if( txt!=null ) {
            if( txt.contains("\t") ) {
                txt = txt.replace('\t', ' ');
                term.setComment(txt);
            }
            if( txt.contains("\n") ) {
                txt = txt.replace('\n', ' ');
                term.setComment(txt);
            }
            if( txt.contains("\r") ) {
                txt = txt.replace('\r', ' ');
                term.setComment(txt);
            }
        }
    }






    /**
     * insert a new dag edge into ONT_DAG table
     * @param parentTermAcc parent term accession id
     * @param childTermAcc child term accession id
     * @param relId relation id
     * @throws Exception if something wrong happens in spring framework
     */
    public int insertDag(String parentTermAcc, String childTermAcc, String relId) throws Exception {

        return dao.upsertDag(parentTermAcc, childTermAcc, relId);
    }

    /**
     * get list of all synonyms for given term
     * @param termAcc term accession id
     * @return list of all synonyms
     * @throws Exception if something wrong happens in spring framework
     */
    public List<TermSynonym> getTermSynonyms(String termAcc) throws Exception {

        List<TermSynonym> results = _termSynonymCache.get(termAcc);
        if( results==null ) {
            results = dao.getTermSynonyms(termAcc);
            _termSynonymCache.put(termAcc, results);
        }
        return results;
    }
    Map<String, List<TermSynonym>> _termSynonymCache = new ConcurrentHashMap<>();


    public List<Term> getRdoTermsBySynonym(String synonymToMatch) throws Exception {
        return dao.getTermsBySynonym("RDO", synonymToMatch, "exact");
    }

    /**
     * get terms synonyms of given type within a specified ontology
     * @param ontologyId id of ontology to be searched for; must not be null
     * @param synonymType synonym type
     * @return List of matching TermSynonym objects; could be empty
     * @throws Exception if something wrong happens in spring framework
     */
    public List<TermSynonym> getActiveSynonymsByType(String ontologyId, String synonymType) throws Exception {

        return dao.getActiveSynonymsByType(ontologyId, synonymType);
    }

    public List<TermSynonym> getActiveSynonymsByName(String ontologyId, String synonymName) throws Exception {
        return dao.getActiveSynonymsByName(ontologyId, synonymName);
    }

    /**
     * insert new synonym for given term
     * @param synonym OntTermSynonym object to be inserted
     * @param source synonym source
     * @throws Exception if something wrong happens in spring framework
     * @return true if synonym was inserted; false if it was skipped
     */
    public boolean insertTermSynonym(TermSynonym synonym, String source) throws Exception {

        synonym.setSource(source==null ? "OBO" : source);

        Logger log = Logger.getLogger("synonymsInserted");
        log.info(synonym.dump("|"));

      //  synonym.setKey(dao.insertTermSynonym(synonym));
        try {
            dao.insertTermSynonym(synonym);
        }catch (Exception e){
            System.err.println("AT SYNONYM:"+synonym.getKey()+"\t"+synonym.getTermAcc());
            e.printStackTrace();
        }
        return true;
    }



    /**
     * delete a collection of term synonyms
     * @param synonyms collection of term synonyms
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int deleteTermSynonyms(Collection<TermSynonym> synonyms) throws Exception {

        if( synonyms.isEmpty() ) {
            return 0;
        }

        Logger log = Logger.getLogger("synonymsDeleted");
        for( TermSynonym syn: synonyms ) {
            log.info(syn.dump("|"));
        }

        return dao.deleteTermSynonyms(synonyms);
    }

    /**
     * get all ontology synonyms for given source modified before given date and time
     *
     * @param ontId id of ontology to be processed
     * @param source source of term synonyms
     * @param dt cut-off date of last modification
     * @return list of TermSynonym objects
     * @throws Exception on spring framework dao failure
     */
    public List<TermSynonym> getTermSynonymsModifiedBefore(String ontId, String source, Date dt) throws Exception{

        return dao.getTermSynonymsModifiedBefore(ontId, source, dt);
    }

    public List<String> getActiveParentTerms(String termAcc) throws Exception {
        String sql = "SELECT t.term_acc FROM ont_dag d,ont_terms t WHERE child_term_acc=? AND parent_term_acc=t.term_acc AND t.is_obsolete=0";
        return StringListQuery.execute(dao, sql, termAcc);
    }

    public Map<String,String> getAnchorTerms(String rdoTermAcc, String anchorTerm) throws Exception {

        Map<String,String> results = new TreeMap<>();
        for( StringMapQuery.MapPair pair: dao.getAnchorTerms(rdoTermAcc, anchorTerm) ) {
            results.put(pair.keyValue, pair.stringValue);
        }
        return results;
    }

    /**
     * get list of accession ids for terms matching the prefix
     * @param prefix term acc prefix
     * @return list of term accession ids
     * @throws Exception if something wrong happens in spring framework
     */
    public List<String> getAllTermAccIds(String prefix) throws Exception {

        if( prefix.equals("*") )
            prefix = "DOID:";

        String sql = "SELECT t.term_acc FROM ont_terms t WHERE t.term_acc like ?";
        return StringListQuery.execute(dao, sql, prefix+"%");
    }



    /**
     * return nr of descendant terms for given term
     * @param termAcc term accession id
     * @return count of descendant terms
     * @throws Exception if something wrong happens in spring framework
     */
    public int getDescendantCount(String termAcc) throws Exception {

        List<String> descendantAccIds = getAllActiveTermDescendantAccIds(termAcc);
        return descendantAccIds.size();
    }

    /**
     * get active (non-obsolete) descendant (child) terms of given term, recursively
     * @param termAcc term accession id
     * @return list of descendant terms
     * @throws Exception if something wrong happens in spring framework
     */
    public List<Term> getAllActiveTermDescendants(String termAcc) throws Exception {
        //return dao.getAllActiveTermDescendants(termAcc);
        String sql = "select * from ont_terms where is_obsolete=0 and " +
            "                term_acc in (" +
            "                with recursive cte as (select child_term_acc   from ont_dag where parent_term_acc=?" +
            "                union all select d.child_term_acc from cte c join ont_dag d on d.parent_term_acc=c.child_term_acc" +
            "                " +
            "                 )" +
            "                 select child_term_acc from cte" +
            "                )";
        return dao.executeTermQuery(sql, termAcc);
    }

    public List<String> getAllActiveTermDescendantAccIds(String termAcc) throws Exception {
        String sql = "select term_acc from ont_terms where is_obsolete=0 and " +
                "                term_acc in (" +
                "                with recursive cte as (select child_term_acc   from ont_dag where parent_term_acc=?" +
                "                union all select d.child_term_acc from cte c join ont_dag d on d.parent_term_acc=c.child_term_acc" +
                "                " +
                "                 )" +
                "                 select child_term_acc from cte" +
                "                )";

        return StringListQuery.execute(dao, sql, termAcc);
    }

    /**
     * return nr of ancestor terms for given term
     * @param termAcc term accession id
     * @return count of ancestor terms
     * @throws Exception if something wrong happens in spring framework
     */
    public int getAncestorCount(String termAcc) throws Exception {
        return dao.getCountOfAncestors(termAcc);
    }



    public TermStats getTermWithStats(String termAcc) throws Exception {
        return getTermWithStats(termAcc, null);
    }

    public TermStats getTermWithStats(String termAcc, String filter) throws Exception {

        TermStats ts = new TermStats();
        ts.setTermAccId(termAcc);
        ts.term = dao.getTermWithStats(termAcc, null,filter);
        return ts;
    }









    int obsoleteOrphanedTerms(String ontId) throws Exception {

        // fix for GO
        if( ontId.equals("GO") ) {
            return obsoleteOrphanedTerms("BP") + obsoleteOrphanedTerms("MF") + obsoleteOrphanedTerms("CC");
        }

        // check if there are orphaned terms
        List<Term> orphanedTerms = dao.getOrphanedTerms(ontId);
        if( orphanedTerms.isEmpty() )
            return 0; // no  orphaned terms

        // never obsolete terms for RDO ontology! only report the issue
        if( getOntologiesWithSuppressedTermObsoletion().contains(ontId) ) {
            System.out.println("OBSOLETE "+ontId+" TERMS: ");
            for( Term term: orphanedTerms ) {
                System.out.println("  "+term.dump("|"));
            }
            return 0;
        }

        // dump terms to be obsoleted into 'obsoletedTerms.log'
        Logger log = Logger.getLogger("obsoletedTerms");
        for( Term term: orphanedTerms ) {
            log.info(term.dump("|"));
        }

        // finally obsolete the orphaned terms
        return dao.obsoleteOrphanedTerms(ontId);
    }

    /**
     * this method examined all term accession ids, and if the terms are not in database, they are inserted
     * @param terms list of terms
     * @param session PipelineSession object
     * @throws Exception if something wrong happens in spring framework
     */
    public synchronized void ensureTermsAreInDatabase(List<Term> terms, PipelineSession session) throws Exception {

        for( Term term: terms ) {
            if( term.getAccId()==null ) {
                continue;
            }
            Term termInDb = getTerm(term.getAccId());
            if( termInDb==null ) {
                insertTerm(term, session);
            }
        }
    }






    public List<TermXRef> getTermXRefs(String termAcc) throws Exception {
        return dao.getTermXRefs(termAcc);
    }

    public int insertTermXRefs(List<TermXRef> xrefs) throws Exception {
        for( TermXRef xref: xrefs ) {
            logInsertedXRefs.info(xref.dump("|"));
            dao.insertTermXRef(xref);
        }
        return xrefs.size();
    }

    public int deleteTermXRefs(List<TermXRef> xrefs) throws Exception {
        for( TermXRef xref: xrefs ) {
            logDeletedXRefs.info(xref.dump("|"));
            dao.deleteTermXRef(xref);
        }
        return xrefs.size();
    }

    public int updateTermXRefDescriptions(List<TermXRef> xrefs) throws Exception {
        for( TermXRef xref: xrefs ) {
            logDescChangedXRefs.info(xref.dump("|"));
            dao.updateTermXRefDescription(xref);
        }
        return xrefs.size();
    }

    /**
     * check if a given term is a descendant, either direct or indirect descendant of a given term;
     * f.e. 'inbred strain' / 'SS' / 'SS/Jr' <br>
     *      term 'SS/Jr' is a (direct) descendant of ancestor term 'SS' <br>
     *      term 'SS/Jr' is a (indirect) descendant of ancestor term 'inbred strain' <br>
     * @param termAcc accession id of the term in question
     * @param ancestorTermAcc accession id of the ancestor term
     * @return true if the term is a descendant of the ancestor term
     * @throws Exception if something wrong happens in spring framework
     */
    public boolean isDescendantOf(String termAcc, String ancestorTermAcc) throws Exception {
        if( termAcc.equals(ancestorTermAcc) ) {
            return false;
        }
        return dao.isDescendantOf(termAcc, ancestorTermAcc);
    }



    public List<StringMapQuery.MapPair> getDagForOntologyPrefix(String ontPrefix) throws Exception {
        String sql = "SELECT parent_term_acc,child_term_acc FROM ont_dag WHERE parent_term_acc like '"+ontPrefix+"%'";
        return StringMapQuery.execute(dao, sql);
    }




    public void checkForCycles(String ontId) throws Exception {
        System.out.println("START check for cycles for ontology "+ontId);

        List<String> accIds;
        if( ontId==null ) {
            String sql = "SELECT term_acc FROM ont_terms WHERE is_obsolete=0";
            accIds = StringListQuery.execute(dao, sql);
        } else {
            String sql = "SELECT term_acc FROM ont_terms WHERE is_obsolete=0 AND ont_id=?";
            accIds = StringListQuery.execute(dao, sql, ontId);
        }
        Collections.shuffle(accIds);
        System.out.println("active terms loaded: "+accIds.size()+" progress interval 15sec");

        long time0 = System.currentTimeMillis();
        final AtomicLong[] stats = {new AtomicLong(time0), new AtomicLong(0)};

        accIds.parallelStream().forEach(accId -> {
        //accIds.stream().forEach(accId -> {

            // test if the newly inserted DAG does not form loops
            try {
                System.out.print("  "+accId);
                int cnt = getDescendantCount(accId);
                System.out.println("  "+cnt);

                // every 10sec print out progress
                long accIdsProcessed = stats[1].incrementAndGet();
                long time2 = System.currentTimeMillis();
                long time1 = stats[0].get();
                if( time2- time1 > 15000 || true ) {
                    stats[0].set(time2);
                    long percent = (100 * accIdsProcessed) / accIds.size();
                    System.out.println(accIdsProcessed + " ("+ percent+"%),  threads=" + Thread.activeCount());
                }

            } catch(Exception e) {
                System.out.println("WARNING: CYCLE found for "+accId);
            }

        });
        long accIdsProcessed = stats[1].get();
        System.out.println(accIdsProcessed + ". threads=" + Thread.activeCount());
        System.out.println("===DONE=== "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }



    public Set<String> getOmimIdsInRdo() throws Exception {
        List<TermSynonym> syns = dao.getActiveSynonymsByNamePattern("RDO", "OMIM:%");
        Set<String> omimIds = new HashSet<>();
        for( TermSynonym tsyn: syns ) {
            omimIds.add(tsyn.getName());
        }
        return omimIds;
    }

    public void setOntologiesWithSuppressedTermObsoletion(Set<String> ontologiesWithSuppressedTermObsoletion) {
        this.ontologiesWithSuppressedTermObsoletion = ontologiesWithSuppressedTermObsoletion;
    }

    public Set<String> getOntologiesWithSuppressedTermObsoletion() {
        return ontologiesWithSuppressedTermObsoletion;
    }
}
