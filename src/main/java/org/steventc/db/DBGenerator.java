package org.steventc.db;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Jdbc;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;


public class DBGenerator {

    public static void main(String[] args) throws Exception {
        GenerationTool.generate(new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.sqlite.JDBC")
                        .withUrl("jdbc:sqlite:/var/tmp/steventc/uscis_visa.db"))
                .withGenerator(new Generator()
                        .withDatabase(new Database())
                        .withGenerate(new Generate()
                                .withPojos(true)
                                .withDaos(true))
                        .withTarget(new Target()
                                .withPackageName("org.steventc.db.generated")
                                .withDirectory("src/main/java"))));
    }
}
