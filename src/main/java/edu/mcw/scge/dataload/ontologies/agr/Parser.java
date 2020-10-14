package edu.mcw.scge.dataload.ontologies.agr;


import org.biojava.nbio.ontology.Ontology;
import org.biojava.nbio.ontology.Term;
import org.biojava.nbio.ontology.io.OboParser;

import java.io.*;
import java.util.Set;

public class Parser {
    public static void main(String[] args) throws FileNotFoundException {
        OboParser parser= new OboParser();
        InputStream inStream =  new FileInputStream("C:/Apps/agr_obo/wbphenotype.obo.txt");

        BufferedReader oboFile = new BufferedReader ( new InputStreamReader( inStream ) );
        try {
            Ontology ontology = parser.parseOBO(oboFile, "my Ontology name", "description of ontology");

            Set<Term> keys = ontology.getTerms();
            for (Object key : keys) {
                Term term = (Term) key;
                if(term.getDescription()!=null && !term.getDescription().equalsIgnoreCase("is_a-relationship")) {
                    System.out.println("TERM:" + term.getName() + "\n" + term.getDescription());
                    System.out.println(term.getAnnotation());
                    Object[] synonyms = term.getSynonyms();
                    for (Object syn : synonyms) {
                        System.out.println(syn);
                    }
                    System.out.println("=======================");
                }
            }
        } catch (Exception e){
            e.printStackTrace();
        }



    }
}
