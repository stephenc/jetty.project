//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.server.session.x;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * JDBCSessionDataStore
 *
 *
 */
public class JDBCSessionDataStore extends AbstractSessionDataStore
{
    final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    protected boolean _initialized = false;
    protected long _lastScavengeTime = 0;
    protected Map<SessionKey, AtomicInteger> _unloadables = new ConcurrentHashMap<>();

    private DatabaseAdaptor _dbAdaptor;
    private SessionTableSchema _sessionTableSchema;

    private int _attempts = -1; // <= 0 means unlimited attempts to load a session
    private boolean _deleteUnloadables = false; //true means if attempts exhausted delete the session

    /**
     * SessionTableSchema
     *
     */
    public static class SessionTableSchema
    {      
        public final static int MAX_INTERVAL_NOT_SET = -999;
        
        protected DatabaseAdaptor _dbAdaptor;
        protected String _tableName = "JettySessions";
        protected String _rowIdColumn = "rowId";
        protected String _idColumn = "sessionId";
        protected String _contextPathColumn = "contextPath";
        protected String _virtualHostColumn = "virtualHost"; 
        protected String _lastNodeColumn = "lastNode";
        protected String _accessTimeColumn = "accessTime"; 
        protected String _lastAccessTimeColumn = "lastAccessTime";
        protected String _createTimeColumn = "createTime";
        protected String _cookieTimeColumn = "cookieTime";
        protected String _lastSavedTimeColumn = "lastSavedTime";
        protected String _expiryTimeColumn = "expiryTime";
        protected String _maxIntervalColumn = "maxInterval";
        protected String _mapColumn = "map";

        
        
        protected void setDatabaseAdaptor(DatabaseAdaptor dbadaptor)
        {
            _dbAdaptor = dbadaptor;
        }
        
        
        public String getTableName()
        {
            return _tableName;
        }
        public void setTableName(String tableName)
        {
            checkNotNull(tableName);
            _tableName = tableName;
        }
        public String getRowIdColumn()
        {       
            if ("rowId".equals(_rowIdColumn) && _dbAdaptor.isRowIdReserved())
                _rowIdColumn = "srowId";
            return _rowIdColumn;
        }
        public void setRowIdColumn(String rowIdColumn)
        {
            checkNotNull(rowIdColumn);
            if (_dbAdaptor == null)
                throw new IllegalStateException ("DbAdaptor is null");
            
            if (_dbAdaptor.isRowIdReserved() && "rowId".equals(rowIdColumn))
                throw new IllegalArgumentException("rowId is reserved word for Oracle");
            
            _rowIdColumn = rowIdColumn;
        }
        public String getIdColumn()
        {
            return _idColumn;
        }
        public void setIdColumn(String idColumn)
        {
            checkNotNull(idColumn);
            _idColumn = idColumn;
        }
        public String getContextPathColumn()
        {
            return _contextPathColumn;
        }
        public void setContextPathColumn(String contextPathColumn)
        {
            checkNotNull(contextPathColumn);
            _contextPathColumn = contextPathColumn;
        }
        public String getVirtualHostColumn()
        {
            return _virtualHostColumn;
        }
        public void setVirtualHostColumn(String virtualHostColumn)
        {
            checkNotNull(virtualHostColumn);
            _virtualHostColumn = virtualHostColumn;
        }
        public String getLastNodeColumn()
        {
            return _lastNodeColumn;
        }
        public void setLastNodeColumn(String lastNodeColumn)
        {
            checkNotNull(lastNodeColumn);
            _lastNodeColumn = lastNodeColumn;
        }
        public String getAccessTimeColumn()
        {
            return _accessTimeColumn;
        }
        public void setAccessTimeColumn(String accessTimeColumn)
        {
            checkNotNull(accessTimeColumn);
            _accessTimeColumn = accessTimeColumn;
        }
        public String getLastAccessTimeColumn()
        {
            return _lastAccessTimeColumn;
        }
        public void setLastAccessTimeColumn(String lastAccessTimeColumn)
        {
            checkNotNull(lastAccessTimeColumn);
            _lastAccessTimeColumn = lastAccessTimeColumn;
        }
        public String getCreateTimeColumn()
        {
            return _createTimeColumn;
        }
        public void setCreateTimeColumn(String createTimeColumn)
        {
            checkNotNull(createTimeColumn);
            _createTimeColumn = createTimeColumn;
        }
        public String getCookieTimeColumn()
        {
            return _cookieTimeColumn;
        }
        public void setCookieTimeColumn(String cookieTimeColumn)
        {
            checkNotNull(cookieTimeColumn);
            _cookieTimeColumn = cookieTimeColumn;
        }
        public String getLastSavedTimeColumn()
        {
            return _lastSavedTimeColumn;
        }
        public void setLastSavedTimeColumn(String lastSavedTimeColumn)
        {
            checkNotNull(lastSavedTimeColumn);
            _lastSavedTimeColumn = lastSavedTimeColumn;
        }
        public String getExpiryTimeColumn()
        {
            return _expiryTimeColumn;
        }
        public void setExpiryTimeColumn(String expiryTimeColumn)
        {
            checkNotNull(expiryTimeColumn);
            _expiryTimeColumn = expiryTimeColumn;
        }
        public String getMaxIntervalColumn()
        {
            return _maxIntervalColumn;
        }
        public void setMaxIntervalColumn(String maxIntervalColumn)
        {
            checkNotNull(maxIntervalColumn);
            _maxIntervalColumn = maxIntervalColumn;
        }
        public String getMapColumn()
        {
            return _mapColumn;
        }
        public void setMapColumn(String mapColumn)
        {
            checkNotNull(mapColumn);
            _mapColumn = mapColumn;
        }
        
        public String getCreateStatementAsString ()
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException ("No DBAdaptor");
            
            String blobType = _dbAdaptor.getBlobType();
            String longType = _dbAdaptor.getLongType();
            
            return "create table "+_tableName+" ("+getRowIdColumn()+" varchar(120), "+_idColumn+" varchar(120), "+
                    _contextPathColumn+" varchar(60), "+_virtualHostColumn+" varchar(60), "+_lastNodeColumn+" varchar(60), "+_accessTimeColumn+" "+longType+", "+
                    _lastAccessTimeColumn+" "+longType+", "+_createTimeColumn+" "+longType+", "+_cookieTimeColumn+" "+longType+", "+
                    _lastSavedTimeColumn+" "+longType+", "+_expiryTimeColumn+" "+longType+", "+_maxIntervalColumn+" "+longType+", "+
                    _mapColumn+" "+blobType+", primary key("+getRowIdColumn()+"))";
        }
        
        public String getCreateIndexOverExpiryStatementAsString (String indexName)
        {
            return "create index "+indexName+" on "+getTableName()+" ("+getExpiryTimeColumn()+")";
        }
        
        public String getCreateIndexOverSessionStatementAsString (String indexName)
        {
            return "create index "+indexName+" on "+getTableName()+" ("+getIdColumn()+", "+getContextPathColumn()+")";
        }
        
        public String getAlterTableForMaxIntervalAsString ()
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException ("No DBAdaptor");
            String longType = _dbAdaptor.getLongType();
            String stem = "alter table "+getTableName()+" add "+getMaxIntervalColumn()+" "+longType;
            if (_dbAdaptor.getDBName().contains("oracle"))
                return stem + " default "+ MAX_INTERVAL_NOT_SET + " not null";
            else
                return stem +" not null default "+ MAX_INTERVAL_NOT_SET;
        }
        
        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }
        public String getInsertSessionStatementAsString()
        {
           return "insert into "+getTableName()+
            " ("+getIdColumn()+", "+getContextPathColumn()+", "+getVirtualHostColumn()+", "+getLastNodeColumn()+
            ", "+getAccessTimeColumn()+", "+getLastAccessTimeColumn()+", "+getCreateTimeColumn()+", "+getCookieTimeColumn()+
            ", "+getLastSavedTimeColumn()+", "+getExpiryTimeColumn()+", "+getMaxIntervalColumn()+", "+getMapColumn()+") "+
            " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        public String getUpdateSessionStatementAsString(SessionKey key)
        {
            String s =  "update "+getTableName()+
                    " set "+getIdColumn()+" = ?, "+getLastNodeColumn()+" = ?, "+getAccessTimeColumn()+" = ?, "+
                    getLastAccessTimeColumn()+" = ?, "+getLastSavedTimeColumn()+" = ?, "+getExpiryTimeColumn()+" = ?, "+
                    getMaxIntervalColumn()+" = ?, "+getMapColumn()+" = ? where ";

            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    return s+getIdColumn()+" = ? and "+
                            getContextPathColumn()+" is null and "+
                            getVirtualHostColumn()+" = ?";

                }
            }

            return s+getIdColumn()+" = ? and "+getContextPathColumn()+
                    " = ? and "+getVirtualHostColumn()+" = ?";
        }

        
        public String getBoundedExpiredSessionsStatementAsString()
        {
            return "select "+getIdColumn()+" from "+getTableName()+" where "+getContextPathColumn()+" = ? and "+getVirtualHostColumn()+" = ? and "+getExpiryTimeColumn()+" >= ? and "+getExpiryTimeColumn()+" <= ?";
        }
        
        public String getMyExpiredSessionsStatementAsString()
        {
            return "select "+getIdColumn()+" from "+getTableName()+" where "+getLastNodeColumn()+" = ? and "+getContextPathColumn()+" = and "+getExpiryTimeColumn()+" >0 and "+getExpiryTimeColumn()+" <= ?";
        }
        
        public String getAllAncientExpiredSessionsAsString()
        {
            return "select "+getIdColumn()+", "+getContextPathColumn()+", "+getVirtualHostColumn()+" from "+getTableName()+" where "+getExpiryTimeColumn()+" >0 and "+getExpiryTimeColumn()+" <= ?";
        }
     
        public PreparedStatement getLoadStatement (Connection connection, SessionKey key)
        throws SQLException
        { 
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");


            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement("select * from "+getTableName()+
                                                                              " where "+getIdColumn()+" = ? and "+
                                                                              getContextPathColumn()+" is null and "+
                                                                              getVirtualHostColumn()+" = ?");
                    statement.setString(1, key.getId());
                    statement.setString(2, key.getVhost());

                    return statement;
                }
            }

            PreparedStatement statement = connection.prepareStatement("select * from "+getTableName()+
                                                                      " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, key.getId());
            statement.setString(2, key.getCanonicalContextPath());
            statement.setString(3, key.getVhost());

            return statement;
        }

        
        
        public PreparedStatement getUpdateStatement (Connection connection, SessionKey key)
        throws SQLException
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String s = "update "+getTableName()+
                    " set "+getIdColumn()+" = ?, "+getLastNodeColumn()+" = ?, "+getAccessTimeColumn()+" = ?, "+
                    getLastAccessTimeColumn()+" = ?, "+getLastSavedTimeColumn()+" = ?, "+getExpiryTimeColumn()+" = ?, "+
                    getMaxIntervalColumn()+" = ?, "+getMapColumn()+" = ? where ";

            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement(s+getIdColumn()+" = ? and "+
                            getContextPathColumn()+" is null and "+
                            getVirtualHostColumn()+" = ?");
                    statement.setString(1, key.getId());
                    statement.setString(2, key.getVhost());
                    return statement;
                }
            }
            PreparedStatement statement = connection.prepareStatement(s+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, key.getId());
            statement.setString(2, key.getCanonicalContextPath());
            statement.setString(3, key.getVhost());

            return statement;
        }
        
        


        public PreparedStatement getDeleteStatement (Connection connection, SessionKey key)
        throws Exception
        { 
            if (_dbAdaptor == null)

                throw new IllegalStateException("No DB adaptor");


            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement("delete from "+getTableName()+
                                                                              " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                              " = ? and "+getVirtualHostColumn()+" = ?");
                    statement.setString(1, key.getId());
                    statement.setString(2, key.getVhost());
                    return statement;
                }
            }

            PreparedStatement statement = connection.prepareStatement("delete from "+getTableName()+
                                                                      " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, key.getId());
            statement.setString(2, key.getCanonicalContextPath());
            statement.setString(3, key.getVhost());

            return statement;

        }

        
        /**
         * Set up the tables in the database
         * @throws SQLException
         */
        /**
         * @throws SQLException
         */
        public void prepareTables()
        throws SQLException
        {
            try (Connection connection = _dbAdaptor.getConnection();
                 Statement statement = connection.createStatement())
            {
                //make the id table
                connection.setAutoCommit(true);
                DatabaseMetaData metaData = connection.getMetaData();
                _dbAdaptor.adaptTo(metaData);
                    
                
                //make the session table if necessary
                String tableName = _dbAdaptor.convertIdentifier(getTableName());
                try (ResultSet result = metaData.getTables(null, null, tableName, null))
                {
                    if (!result.next())
                    {
                        //table does not exist, so create it
                        statement.executeUpdate(getCreateStatementAsString());
                    }
                    else
                    {
                        //session table exists, check it has maxinterval column
                        ResultSet colResult = null;
                        try
                        {
                            colResult = metaData.getColumns(null, null,
                                                            _dbAdaptor.convertIdentifier(getTableName()), 
                                                            _dbAdaptor.convertIdentifier(getMaxIntervalColumn()));
                        }
                        catch (SQLException s)
                        {
                            LOG.warn("Problem checking if "+getTableName()+
                                     " table contains "+getMaxIntervalColumn()+" column. Ensure table contains column definition: \""
                                    + getMaxIntervalColumn()+" long not null default -999\"");
                            throw s;
                        }
                        try
                        {
                            if (!colResult.next())
                            {
                                try
                                {
                                    //add the maxinterval column
                                    statement.executeUpdate(getAlterTableForMaxIntervalAsString());
                                }
                                catch (SQLException s)
                                {
                                    LOG.warn("Problem adding "+getMaxIntervalColumn()+
                                             " column. Ensure table contains column definition: \""+getMaxIntervalColumn()+
                                             " long not null default -999\"");
                                    throw s;
                                }
                            }
                        }
                        finally
                        {
                            colResult.close();
                        }
                    }
                }
                //make some indexes on the JettySessions table
                String index1 = "idx_"+getTableName()+"_expiry";
                String index2 = "idx_"+getTableName()+"_session";

                boolean index1Exists = false;
                boolean index2Exists = false;
                try (ResultSet result = metaData.getIndexInfo(null, null, tableName, false, false))
                {
                    while (result.next())
                    {
                        String idxName = result.getString("INDEX_NAME");
                        if (index1.equalsIgnoreCase(idxName))
                            index1Exists = true;
                        else if (index2.equalsIgnoreCase(idxName))
                            index2Exists = true;
                    }
                }
                if (!index1Exists)
                    statement.executeUpdate(getCreateIndexOverExpiryStatementAsString(index1));
                if (!index2Exists)
                    statement.executeUpdate(getCreateIndexOverSessionStatementAsString(index2));
            }
        }
    }
    
    
   
  
    public JDBCSessionDataStore ()
    {
        super ();
    }

  

    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#newSessionData(org.eclipse.jetty.server.session.x.SessionKey, long, long, long, long)
     */
    @Override
    public SessionData newSessionData(SessionKey key, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new SessionData(key.getId(), key.getCanonicalContextPath(), key.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }



    @Override
    protected void doStart() throws Exception
    {         
        if (_dbAdaptor == null)
            throw new IllegalStateException("No jdbc config");
        
        _unloadables.clear();
        initialize();
        super.doStart();
    }




    @Override
    protected void doStop() throws Exception
    {
        _unloadables.clear();
        super.doStop();
    }




    public void initialize () throws Exception
    {
        if (!_initialized)
        {
            _initialized = true;
   
            //taking the defaults if one not set
            if (_sessionTableSchema == null)
                _sessionTableSchema = new SessionTableSchema();
            
            _dbAdaptor.initialize();
            _sessionTableSchema.setDatabaseAdaptor(_dbAdaptor);
            _sessionTableSchema.prepareTables();
        }

    }


 
    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#load(org.eclipse.jetty.server.session.x.SessionKey)
     */
    @Override
    public SessionData load(SessionKey key) throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection();
                PreparedStatement statement = _sessionTableSchema.getLoadStatement(connection, key);
                ResultSet result = statement.executeQuery())
        {
            SessionData data = null;
            if (result.next())
            {                    
                data = newSessionData(key,
                                      result.getLong(_sessionTableSchema.getCreateTimeColumn()), 
                                      result.getLong(_sessionTableSchema.getAccessTimeColumn()), 
                                      result.getLong(_sessionTableSchema.getLastAccessTimeColumn()), 
                                      result.getLong(_sessionTableSchema.getMaxIntervalColumn()));
                data.setCookieSet(result.getLong(_sessionTableSchema.getCookieTimeColumn()));
                data.setLastNode(result.getString(_sessionTableSchema.getLastNodeColumn()));
                data.setLastSaved(result.getLong(_sessionTableSchema.getLastSavedTimeColumn()));
                data.setExpiry(result.getLong(_sessionTableSchema.getExpiryTimeColumn()));
                data.setContextPath(result.getString(_sessionTableSchema.getContextPathColumn())); //TODO needed? this is part of the key now
                data.setVhost(result.getString(_sessionTableSchema.getVirtualHostColumn())); //TODO needed??? this is part of the key now

                try (InputStream is = _dbAdaptor.getBlobInputStream(result, _sessionTableSchema.getMapColumn());
                        ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(is))
                {
                    Object o = ois.readObject();
                    data.putAllAttributes((Map<String,Object>)o);
                }
                catch (Exception e)
                {
                    if (getLoadAttempts() > 0)
                    {
                        incLoadAttempt (key);
                    }
                    throw new UnreadableSessionDataException (key, e);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("LOADED session {}", data);
            }
            else
                if (LOG.isDebugEnabled())
                    LOG.debug("No session {}", key.getId());
            return data;
        }
        catch (UnreadableSessionDataException e)
        {
            if (getLoadAttempts() > 0 && loadAttemptsExhausted(key))
            {
                try
                {
                    delete (key);
                    _unloadables.remove(key);
                }
                catch (Exception x)
                {
                    LOG.warn("Problem deleting unloadable session {}", key);
                }

            }
            throw e;
        }
    }



    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(SessionKey key) throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = _sessionTableSchema.getDeleteStatement(connection, key))
        {
            connection.setAutoCommit(true);
            int rows = statement.executeUpdate();
            if (LOG.isDebugEnabled())
                LOG.debug("Deleted Session {}:{}",key,(rows>0));
            return rows > 0;
        }
    }




    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionDataStore#doStore()
     */
    @Override
    public void doStore(SessionKey key, SessionData data) throws Exception
    {
        if (data==null || key==null)
            return;

        try (Connection connection = _dbAdaptor.getConnection())        
        {
            connection.setAutoCommit(true);
            
            //If last saved field not set, then this is a fresh session that has never been persisted
            if (data.getLastSaved() <= 0)
            {     
                doInsert(connection, key, data);
            }
            else
            {
                doUpdate(connection, key, data);            
            }
         
        }

    }


    private void doInsert (Connection connection, SessionKey key, SessionData data) 
    throws Exception
    {
        String s = _sessionTableSchema.getInsertSessionStatementAsString();

        try  (PreparedStatement statement = connection.prepareStatement(s))
        {

            long now = System.currentTimeMillis();


            statement.setString(1, key.getId()); //session id
            statement.setString(2, key.getCanonicalContextPath()); //context path
            statement.setString(3, key.getVhost()); //first vhost
            statement.setString(4, data.getLastNode());//my node id
            statement.setLong(5, data.getAccessed());//accessTime
            statement.setLong(6, data.getLastAccessed()); //lastAccessTime
            statement.setLong(7, data.getCreated()); //time created
            statement.setLong(8, data.getCookieSet());//time cookie was set
            statement.setLong(9, now); //last saved time
            statement.setLong(10, data.getExpiry());
            statement.setLong(11, data.getMaxInactiveMs());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(data.getAllAttributes());
            oos.flush();
            byte[] bytes = baos.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            statement.setBinaryStream(12, bais, bytes.length);//attribute map as blob
            statement.executeUpdate();
            data.setLastSaved(now);
            if (LOG.isDebugEnabled())
                LOG.debug("Inserted session "+data);
        }
    }

    private void doUpdate (Connection connection, SessionKey key, SessionData data)
    throws Exception
    {
       try (PreparedStatement statement = connection.prepareStatement(_sessionTableSchema.getUpdateSessionStatementAsString(key)))
       {

           long now = System.currentTimeMillis();
           
  
           statement.setString(1, data.getLastNode());//should be my node id
           statement.setLong(2, data.getAccessed());//accessTime
           statement.setLong(3, data.getLastAccessed()); //lastAccessTime
           statement.setLong(4, now); //last saved time
           statement.setLong(5, data.getExpiry());
           statement.setLong(6, data.getMaxInactiveMs());

           ByteArrayOutputStream baos = new ByteArrayOutputStream();
           ObjectOutputStream oos = new ObjectOutputStream(baos);
           oos.writeObject(data.getAllAttributes());
           oos.flush();
           byte[] bytes = baos.toByteArray();
           ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
           statement.setBinaryStream(7, bais, bytes.length);//attribute map as blob

           if ((key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath())) && _dbAdaptor.isEmptyStringNull())
           {
               statement.setString(8, key.getId());
               statement.setString(9, key.getVhost()); 
           }
           else
           {
               statement.setString(8, key.getId());
               statement.setString(9, key.getCanonicalContextPath());
               statement.setString(10, key.getVhost());
           }
           
           statement.executeUpdate();

           data.setLastSaved(now);
           if (LOG.isDebugEnabled())
               LOG.debug("Updated session "+data);
       }
    }


    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#getExpired()
     */
    @Override
    public Set<SessionKey> getExpired(Set<SessionKey> candidates)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Scavenge sweep started at "+System.currentTimeMillis());

        long now = System.currentTimeMillis();
        
        //first time we're called, don't scavenge
        if (_lastScavengeTime == 0)
        {
            _lastScavengeTime = now;
            return Collections.emptySet();
        }
        
        //we actually ignore the list of candidates as the database has the definitive list of sessions
        
        long interval = now - _lastScavengeTime;

        String cpath = SessionKey.getContextPath(_context);
        String vhost = SessionKey.getVirtualHost(_context);
        
        Set<SessionKey> expiredSessionKeys = new HashSet<SessionKey>();
        try (Connection connection = _dbAdaptor.getConnection())
        {
            connection.setAutoCommit(true);
            
            /*
             * 1. Select sessions for our node and context that have expired since a grace interval
             */
            long upperBound = _lastScavengeTime; //grace interval is 1 scavenge interval
            if (LOG.isDebugEnabled())
                LOG.debug ("{}- Pass 1: Searching for sessions for node {} and context {} expired before {}", _node, _node, cpath, upperBound);

            
            try (PreparedStatement statement = connection.prepareStatement(_sessionTableSchema.getMyExpiredSessionsStatementAsString()))
            {
                statement.setString(1, cpath);
                statement.setString(2,  vhost);
                statement.setLong(3, upperBound);
                try (ResultSet result = statement.executeQuery())
                {
                    while (result.next())
                    {
                        String sessionId = result.getString(_sessionTableSchema.getIdColumn());
                        expiredSessionKeys.add(SessionKey.getKey(sessionId, cpath, vhost));
                        if (LOG.isDebugEnabled()) LOG.debug (cpath+"- Found expired sessionId="+sessionId);
                    }
                }
            }

            /*
             *  2. Select sessions for any node or context that have expired a long time ago (ie at least 3 intervals ago)
             */
            try (PreparedStatement selectExpiredSessions = connection.prepareStatement(_sessionTableSchema.getAllAncientExpiredSessionsAsString()))
            {
              
                upperBound = _lastScavengeTime - (3 * interval);
                if (upperBound > 0)
                {
                    if (LOG.isDebugEnabled()) LOG.debug("{}- Pass 2: Searching for sessions expired before {}",_node, upperBound);
                    selectExpiredSessions.setLong(1, upperBound);
                    try (ResultSet result = selectExpiredSessions.executeQuery())
                    {
                        while (result.next())
                        {
                            String sessionId = result.getString(_sessionTableSchema.getIdColumn());
                            String ctxtpth = result.getString(_sessionTableSchema.getContextPathColumn());
                            String vh = result.getString(_sessionTableSchema.getVirtualHostColumn());
                            expiredSessionKeys.add(SessionKey.getKey(sessionId, ctxtpth, vh));
                            if (LOG.isDebugEnabled()) LOG.debug ("{}- Found expired sessionId=",_node, sessionId);
                        }
                    }
                }
            }

            return expiredSessionKeys;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return expiredSessionKeys; //return whatever we got
        } 
        finally
        {
            _lastScavengeTime = now;
        }
    }
    
    
    
    public void setDatabaseAdaptor (DatabaseAdaptor dbAdaptor)
    {
        checkStarted();
        _dbAdaptor = dbAdaptor;
    }
    
    public void setSessionTableSchema (SessionTableSchema schema)
    {
        checkStarted();
        _sessionTableSchema = schema;
    }

    public void setLoadAttempts (int attempts)
    {
        checkStarted();
        _attempts = attempts;
    }

    public int getLoadAttempts ()
    {
        return _attempts;
    }
    
    public boolean loadAttemptsExhausted (SessionKey key)
    {
        AtomicInteger i = _unloadables.get(key);
        if (i == null)
            return false;
        return (i.get() >= _attempts);
    }
    
    public void setDeleteUnloadableSessions (boolean delete)
    {
        checkStarted();
        _deleteUnloadables = delete;
    }
    
    protected void incLoadAttempt (SessionKey key)
    {
        AtomicInteger i = new AtomicInteger(0);
        AtomicInteger count = _unloadables.putIfAbsent(key, i);
        if (count == null)
            count = i;
        count.incrementAndGet();
    }
    
    protected void checkStarted () throws IllegalStateException
    {
        if (isStarted())
            throw new IllegalStateException("Already started");
    }
}







