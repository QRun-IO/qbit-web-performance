/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.webperf.store;


import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.webperf.WebPerfQBitConfig;
import com.kingsrook.qbits.webperf.WebPerfSchema;


/*******************************************************************************
 ** JDBC access to the QBit's tables for its processes and widgets. The
 ** measurement tables are read-only in the UI (no insert or update
 ** capability), so the QBit writes them here, not through QQQ table actions.
 ** The SQL is portable between PostgreSQL and H2; table names come from the
 ** config's prefix through {placeholders} (never user input).
 **
 ** Timestamps are bound as UTC wall-clock Timestamps, the way the QQQ RDBMS
 ** module binds Instant fields, so QQQ screens read them back unchanged.
 *******************************************************************************/
public class WebPerfStore
{
   /*******************************************************************************
    ** Opens connections to the QBit's backend.
    *******************************************************************************/
   @FunctionalInterface
   public interface ConnectionSupplier
   {
      /*******************************************************************************
       ** A new connection (closed by the caller).
       **
       ** @return the connection
       ** @throws SQLException on failure
       *******************************************************************************/
      Connection get() throws SQLException;
   }



   private final WebPerfQBitConfig  config;
   private final ConnectionSupplier connections;
   private Connection transactionConnection;



   /*******************************************************************************
    ** Work sharing one database transaction.
    *******************************************************************************/
   @FunctionalInterface
   public interface TransactionWork<T>
   {
      /*******************************************************************************
       ** Execute against the transaction's store.
       *******************************************************************************/
      T run(WebPerfStore store) throws SQLException;
   }



   /*******************************************************************************
    ** Commit related writes together; roll back every write on failure.
    *******************************************************************************/
   public <T> T transaction(TransactionWork<T> work) throws SQLException
   {
      if(transactionConnection != null)
      {
         return (work.run(this));
      }
      try(Connection connection = connections.get())
      {
         boolean autoCommit = connection.getAutoCommit();
         connection.setAutoCommit(false);
         WebPerfStore transactional = new WebPerfStore(config, connections);
         transactional.transactionConnection = connection;
         try
         {
            T result = work.run(transactional);
            connection.commit();
            return (result);
         }
         catch(SQLException | RuntimeException e)
         {
            connection.rollback();
            throw (e);
         }
         finally
         {
            connection.setAutoCommit(autoCommit);
         }
      }
   }



   /*******************************************************************************
    ** Own a connection only outside an enclosing transaction.
    *******************************************************************************/
   private ConnectionLease lease() throws SQLException
   {
      return (new ConnectionLease(transactionConnection == null ? connections.get() : transactionConnection, transactionConnection == null));
   }



   /*******************************************************************************
    ** A statement's connection lifetime.
    *******************************************************************************/
   private record ConnectionLease(Connection connection, boolean owned) implements AutoCloseable
   {
      /*******************************************************************************
       ** Leave an enclosing transaction's connection open.
       *******************************************************************************/
      @Override
      public void close() throws SQLException
      {
         if(owned)
         {
            connection.close();
         }
      }
   }



   /*******************************************************************************
    ** A store over a config's tables.
    **
    ** @param config      the config (table prefix)
    ** @param connections the connection supplier
    *******************************************************************************/
   public WebPerfStore(WebPerfQBitConfig config, ConnectionSupplier connections)
   {
      this.config = config;
      this.connections = connections;
   }



   /*******************************************************************************
    ** Replace {table} placeholders with prefixed table names.
    **
    ** @param template SQL with placeholders
    ** @return the SQL
    *******************************************************************************/
   public String sql(String template)
   {
      String sql = template;
      for(String base : WebPerfSchema.TABLES)
      {
         sql = sql.replace("{" + base + "}", config.applyPrefix(base));
      }
      return (sql);
   }



   /*******************************************************************************
    ** Run an update; returns the row count.
    **
    ** @param template SQL with placeholders
    ** @param params   parameters
    ** @return rows changed
    ** @throws SQLException on failure
    *******************************************************************************/
   public int update(String template, Object... params) throws SQLException
   {
      try(ConnectionLease lease = lease(); PreparedStatement statement = lease.connection().prepareStatement(sql(template)))
      {
         bind(statement, params);
         return (statement.executeUpdate());
      }
   }



   /*******************************************************************************
    ** Insert one row and return its generated id.
    **
    ** @param table  the table base name
    ** @param values column name to value, in order
    ** @return the id
    ** @throws SQLException on failure
    *******************************************************************************/
   public long insert(String table, Map<String, Object> values) throws SQLException
   {
      Map<String, Object> row = new LinkedHashMap<>(values);
      Instant             now = Instant.now();
      row.putIfAbsent("create_date", now);
      row.putIfAbsent("modify_date", now);

      String columns      = String.join(", ", row.keySet());
      String placeholders = String.join(", ", java.util.Collections.nCopies(row.size(), "?"));
      try(ConnectionLease lease = lease();
         PreparedStatement statement = lease.connection().prepareStatement("INSERT INTO " + config.applyPrefix(table) + " (" + columns + ") VALUES (" + placeholders + ")",
            Statement.RETURN_GENERATED_KEYS))
      {
         bind(statement, row.values().toArray());
         statement.executeUpdate();
         try(ResultSet keys = statement.getGeneratedKeys())
         {
            keys.next();
            return (keys.getLong(1));
         }
      }
   }



   /*******************************************************************************
    ** Run a query; rows as column-name (lower case) to value maps.
    **
    ** @param template SQL with placeholders
    ** @param params   parameters
    ** @return the rows
    ** @throws SQLException on failure
    *******************************************************************************/
   public List<Map<String, Object>> query(String template, Object... params) throws SQLException
   {
      try(ConnectionLease lease = lease(); PreparedStatement statement = lease.connection().prepareStatement(sql(template)))
      {
         bind(statement, params);
         List<Map<String, Object>> rows = new ArrayList<>();
         try(ResultSet resultSet = statement.executeQuery())
         {
            ResultSetMetaData metaData = resultSet.getMetaData();
            while(resultSet.next())
            {
               Map<String, Object> row = new LinkedHashMap<>();
               for(int i = 1; i <= metaData.getColumnCount(); i++)
               {
                  Object value = resultSet.getObject(i);
                  if(value instanceof Timestamp timestamp)
                  {
                     value = timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
                  }
                  else if(value instanceof Date date)
                  {
                     value = date.toLocalDate();
                  }
                  row.put(metaData.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), value);
               }
               rows.add(row);
            }
         }
         return (rows);
      }
   }



   /*******************************************************************************
    ** Bind parameters: Instants as UTC wall-clock Timestamps, LocalDates as
    ** Dates, byte arrays as bytes, nulls as NULL.
    **
    ** @param statement the statement
    ** @param params    the parameters
    ** @throws SQLException on failure
    *******************************************************************************/
   static void bind(PreparedStatement statement, Object... params) throws SQLException
   {
      for(int i = 0; i < params.length; i++)
      {
         Object param = params[i];
         if(param == null)
         {
            statement.setNull(i + 1, Types.NULL);
         }
         else if(param instanceof Instant instant)
         {
            statement.setTimestamp(i + 1, utc(instant));
         }
         else if(param instanceof LocalDate localDate)
         {
            statement.setDate(i + 1, Date.valueOf(localDate));
         }
         else if(param instanceof byte[] bytes)
         {
            statement.setBytes(i + 1, bytes);
         }
         else if(param instanceof Double d)
         {
            statement.setBigDecimal(i + 1, BigDecimal.valueOf(d));
         }
         else
         {
            statement.setObject(i + 1, param);
         }
      }
   }



   /*******************************************************************************
    ** An Instant as a UTC wall-clock Timestamp.
    **
    ** @param instant the instant
    ** @return the timestamp
    *******************************************************************************/
   public static Timestamp utc(Instant instant)
   {
      return (Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC)));
   }



   /*******************************************************************************
    ** A value as a Long, or null.
    **
    ** @param value the value
    ** @return the long or null
    *******************************************************************************/
   public static Long asLong(Object value)
   {
      return (value instanceof Number number ? number.longValue() : null);
   }



   /*******************************************************************************
    ** A value as an Integer, or null.
    **
    ** @param value the value
    ** @return the integer or null
    *******************************************************************************/
   public static Integer asInt(Object value)
   {
      return (value instanceof Number number ? number.intValue() : null);
   }



   /*******************************************************************************
    ** A value as a Double, or null.
    **
    ** @param value the value
    ** @return the double or null
    *******************************************************************************/
   public static Double asDouble(Object value)
   {
      return (value instanceof Number number ? number.doubleValue() : null);
   }



   /*******************************************************************************
    ** Getter for config.
    **
    ** @return the config
    *******************************************************************************/
   public WebPerfQBitConfig getConfig()
   {
      return (config);
   }
}
