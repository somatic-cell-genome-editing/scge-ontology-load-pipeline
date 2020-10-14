package edu.mcw.scge.dataload.ontologies;

import edu.mcw.scge.datamodel.ontologyx.Relation;
import edu.mcw.scge.datamodel.ontologyx.Term;
import edu.mcw.scge.datamodel.ontologyx.TermSynonym;

import org.apache.log4j.Logger;

import java.util.Map;


public class DataLoader  {

    private OntologyDAO dao;
    protected final Logger logger = Logger.getLogger("data_loader");

    public void process(Record r) throws Exception {
        Record rec = (Record) r;
        Term term = rec.getTerm();

    //    logger.debug("processing ["+rec.getRecNo()+".] "+term.getAccId());

        if( rec.isFlagSet("NO_ACC_ID") ) {

          //  getSession().incrementCounter("TERMS_MISSING_ACC_ID", 1);
            return;
        }

        if( rec.isFlagSet("UPDATE") ) {
            logger.debug("UPDATE ACC_ID:"+term.getAccId()+", TERM:"+term.getTerm()+", ISOBSOLETE:"+term.getObsolete()+", DEFINITION:"+term.getDefinition()+", COMMENT:"+term.getComment());
            dao.updateTerm(term);
            //getSession().incrementCounter("TERMS_UPDATED", 1);
        }

        if( !rec.getEdges().isEmpty() ) {

            for( Map.Entry<String, Relation> entry: rec.getEdges().entrySet() ) {
                System.out.println(rec.getTerm().getAccId()+"\t"+ rec.getTerm().getTerm());
                String parentTermAcc = entry.getKey();
                String childTermAcc = term.getAccId();
                System.out.println(parentTermAcc+"\t"+ childTermAcc);

                if( parentTermAcc==null || childTermAcc==null ) {
                    System.out.println("WARN: NULL in dag: ["+parentTermAcc+"]==>["+childTermAcc+"]");
                    continue;
                }
                if( parentTermAcc.equals(childTermAcc) ) {
                    System.out.println("WARN: parent term acc equals child term acc: "+parentTermAcc);
                }
                else {
                    String relId = Relation.getRelIdFromRel(entry.getValue());
                    System.out.println("UPSERT DAG ("+parentTermAcc+","+childTermAcc+","+entry.getValue()+")");
                    dao.insertDag(parentTermAcc, childTermAcc, relId);
                //    getSession().incrementCounter("DAG_EDGES_INCOMING", 1);
                }
            }
        }

        // handle synonyms
        loadSynonyms(rec);

        // handle dbxrefs
        rec.loadXRefs(dao, null);
    }

    void loadSynonyms(Record rec) throws Exception {

        // insert new synonyms
        if( !rec.getSynonymsForInsert().isEmpty() ) {
            for( TermSynonym synonym: rec.getSynonymsForInsert() ) {

                if( dao.insertTermSynonym(synonym, null) ) {
                 //   getSession().incrementCounter("SYNONYMS_INSERTED", 1);
                } else {
                    //getSession().incrementCounter("SYNONYMS_FOR_INSERT_SKIPPED", 1);
                }
            }
        }



        // delete synonyms
        if( !rec.getSynonymsForDelete().isEmpty() ) {
            int deleted = dao.deleteTermSynonyms(rec.getSynonymsForDelete());
            int skipped = rec.getSynonymsForDelete().size() - deleted;
            //getSession().incrementCounter("SYNONYMS_DELETED", deleted);
         //   getSession().incrementCounter("SYNONYMS_FOR_DELETE_SKIPPED", skipped);
        }
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }
}
