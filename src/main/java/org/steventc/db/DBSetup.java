package org.steventc.db;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.meta.derby.sys.Tables;
import org.steventc.db.generated.tables.CaseHistory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

public class DBSetup {

    public static void main(String[] args) throws SQLException {

        // Create a connection to the SQLite database
        var connection = DriverManager.getConnection("jdbc:sqlite:/var/tmp/steventc/uscis_visa.db");

        var dslContext = DSL.using(connection, SQLDialect.SQLITE);



        dslContext.createTable("case")
                .column("case_number", SQLDataType.VARCHAR)
                .column("latest_update", SQLDataType.VARCHAR)
                .column("update_date", SQLDataType.LOCALDATE)
                .column("case_type", SQLDataType.VARCHAR)
                .primaryKey("case_number").execute();


        dslContext.createTable("case_history")
                .column("case_number", SQLDataType.VARCHAR)
                .column("status", SQLDataType.VARCHAR)
                .column("update_date", SQLDataType.LOCALDATE)
                .primaryKey("case_number").execute();

        System.out.println("done");


    }
}
