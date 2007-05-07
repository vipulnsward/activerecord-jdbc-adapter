/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2007 Ola Bini <ola@ologix.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.HashMap;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBigDecimal;
import org.jruby.RubyClass;
import org.jruby.RubyHash;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyString;
import org.jruby.javasupport.JavaObject;
import org.jruby.runtime.Block;
import org.jruby.runtime.CallbackFactory;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.load.BasicLibraryService;
import org.jruby.util.ByteList;

public class JdbcAdapterInternalService implements BasicLibraryService {
    public boolean basicLoad(final Ruby runtime) throws IOException {
        RubyClass cJdbcConn = ((RubyModule)(runtime.getModule("ActiveRecord").getConstant("ConnectionAdapters"))).
            defineClassUnder("JdbcConnection",runtime.getObject(),runtime.getObject().getAllocator());

        CallbackFactory cf = runtime.callbackFactory(JdbcAdapterInternalService.class);
        cJdbcConn.defineMethod("unmarshal_result",cf.getSingletonMethod("unmarshal_result", IRubyObject.class));
        cJdbcConn.defineFastMethod("set_connection",cf.getFastSingletonMethod("set_connection", IRubyObject.class));
        cJdbcConn.defineFastMethod("execute_update",cf.getFastSingletonMethod("execute_update", IRubyObject.class));
        cJdbcConn.defineFastMethod("execute_query",cf.getFastOptSingletonMethod("execute_query")); 
        cJdbcConn.defineFastMethod("execute_insert",cf.getFastSingletonMethod("execute_insert", IRubyObject.class));
        cJdbcConn.defineFastMethod("execute_id_insert",cf.getFastSingletonMethod("execute_id_insert", IRubyObject.class, IRubyObject.class));
        cJdbcConn.defineFastMethod("primary_keys",cf.getFastSingletonMethod("primary_keys", IRubyObject.class));
        cJdbcConn.defineFastMethod("set_native_database_types",cf.getFastSingletonMethod("set_native_database_types"));
        cJdbcConn.defineFastMethod("native_database_types",cf.getFastSingletonMethod("native_database_types"));
        cJdbcConn.defineFastMethod("begin",cf.getFastSingletonMethod("begin"));
        cJdbcConn.defineFastMethod("commit",cf.getFastSingletonMethod("commit"));
        cJdbcConn.defineFastMethod("rollback",cf.getFastSingletonMethod("rollback"));
        cJdbcConn.defineFastMethod("database_name",cf.getFastSingletonMethod("database_name"));
        cJdbcConn.defineFastMethod("columns",cf.getFastOptSingletonMethod("columns"));
        cJdbcConn.defineFastMethod("mysql_quote_string",cf.getFastSingletonMethod("mysql_quote_string",IRubyObject.class));
        cJdbcConn.defineMethod("tables",cf.getSingletonMethod("tables"));
        return true;
    }

    private static ResultSet intoResultSet(IRubyObject inp) {
        return (ResultSet)((inp instanceof JavaObject ? ((JavaObject)inp) : (((JavaObject)(inp.getInstanceVariable("@java_object"))))).getValue());
    }   

    public static IRubyObject set_connection(IRubyObject recv, IRubyObject conn) {
        Connection c = (Connection)recv.dataGetStruct();
        if(c != null) {
            try {
                c.close();
            } catch(Exception e) {}
        }
        recv.setInstanceVariable("@connection",conn);
        c = (Connection)(((JavaObject)conn.getInstanceVariable("@java_object")).getValue());
        recv.dataWrapStruct(c);
        return recv;
    }

    public static IRubyObject tables(IRubyObject recv, Block table_filter) throws SQLException, IOException {
        Ruby runtime = recv.getRuntime();
        while(true) {
            Connection c = (Connection)recv.dataGetStruct();
            try {
                ResultSet rs = c.getMetaData().getTables(null,null,null,null);
                if(table_filter.isGiven()) {
                    RubyString ss = runtime.newString("table_name");
                    List arr = new ArrayList();
                    RubyArray aa = (RubyArray)unmarshal_result(recv, JavaObject.wrap(runtime, rs), table_filter);
                    for(int i=0,j=aa.size();i<j;i++) {
                        arr.add(((RubyString)(((RubyHash)aa.eltInternal(i)).aref(ss))).downcase());
                    }
                    return runtime.newArray(arr);
                } else {
                    List arr = new ArrayList();
                    while(rs.next()) {
                        arr.add(runtime.newString(rs.getString(3).toLowerCase()));
                    }
                    return runtime.newArray(arr);
                }
            } catch(SQLException e) {
                if(c.isClosed()) {
                    recv.callMethod(recv.getRuntime().getCurrentContext(),"reconnect!");
                    continue;
                }
                throw e;
            }
        }
    }

    /*
      # The hacks in this method is needed because of a bug in Rails. Check
      # out type_to_sql in schema_definitions.rb and see if you can see it... =)
    */
    public static IRubyObject native_database_types(IRubyObject recv) {
        RubyHash tps = (RubyHash)recv.getInstanceVariable("@tps");
        Map val = new HashMap();
        Ruby runtime = recv.getRuntime();
        IRubyObject nameSym = runtime.newSymbol("name");
        for(Iterator iter = tps.directEntrySet().iterator();iter.hasNext();) {
            Map.Entry me = (Map.Entry)iter.next();
            IRubyObject v = (IRubyObject)me.getValue();
            if(v instanceof RubyHash) {
                RubyHash v2 = (RubyHash)(v.dup());
                v2.put(nameSym, ((IRubyObject)(v2.fastARef(nameSym))).dup());
                val.put(me.getKey(), v2);
            } else {
                val.put(me.getKey(), ((IRubyObject)v).dup());
            }
        }
        return RubyHash.newHash(runtime, val, runtime.getNil());
    }    

    public static IRubyObject set_native_database_types(IRubyObject recv) throws SQLException, IOException {
        Ruby runtime = recv.getRuntime();
        ThreadContext ctx = runtime.getCurrentContext();
        IRubyObject types = unmarshal_result_downcase(recv, ((Connection)recv.dataGetStruct()).getMetaData().getTypeInfo());
        recv.setInstanceVariable("@native_types", 
                                 ((RubyModule)(runtime.getModule("ActiveRecord").getConstant("ConnectionAdapters"))).
                                 getConstant("JdbcTypeConverter").callMethod(ctx,"new", types).
                                 callMethod(ctx, "choose_best_types"));
        return runtime.getNil();
    }

    public static IRubyObject database_name(IRubyObject recv) throws SQLException {
        return recv.getRuntime().newString(((Connection)recv.dataGetStruct()).getCatalog());
    }

    public static IRubyObject begin(IRubyObject recv) throws SQLException {
        ((Connection)recv.dataGetStruct()).setAutoCommit(false);
        return recv.getRuntime().getNil();
    }

    public static IRubyObject commit(IRubyObject recv) throws SQLException {
        try {
            ((Connection)recv.dataGetStruct()).commit();
            return recv.getRuntime().getNil();
        } finally {
            ((Connection)recv.dataGetStruct()).setAutoCommit(true);
        }
    }

    public static IRubyObject rollback(IRubyObject recv) throws SQLException {
        try {
            ((Connection)recv.dataGetStruct()).rollback();
            return recv.getRuntime().getNil();
        } finally {
            ((Connection)recv.dataGetStruct()).setAutoCommit(true);
        }
    }

    public static IRubyObject columns(IRubyObject recv, IRubyObject[] args) throws SQLException, IOException {
        String table_name = args[0].toString();
        while(true) {
            Connection c = (Connection)recv.dataGetStruct();
            try {
                DatabaseMetaData metadata = c.getMetaData();
                if(metadata.storesUpperCaseIdentifiers()) {
                    table_name = table_name.toUpperCase();
                } else if(metadata.storesLowerCaseIdentifiers()) {
                    table_name = table_name.toLowerCase();
                }
                ResultSet results = metadata.getColumns(null,null,table_name,null);
                return unmarshal_columns(recv, metadata, results);
            } catch(SQLException e) {
                if(c.isClosed()) {
                    recv.callMethod(recv.getRuntime().getCurrentContext(),"reconnect!");
                    continue;
                }
                throw e;
            }
        }    
    }

    private static IRubyObject unmarshal_columns(IRubyObject recv, DatabaseMetaData metadata, ResultSet rs) throws SQLException, IOException {
        List columns = new ArrayList();
        boolean isDerby = metadata.getClass().getName().indexOf("derby") != -1;
        Ruby runtime = recv.getRuntime();
        ThreadContext ctx = runtime.getCurrentContext();

        RubyHash tps = ((RubyHash)(recv.callMethod(ctx, "adapter").callMethod(ctx,"native_database_types")));

        IRubyObject jdbcCol = ((RubyModule)(runtime.getModule("ActiveRecord").getConstant("ConnectionAdapters"))).getConstant("JdbcColumn");

        while(rs.next()) {
            String column_name = rs.getString(4);
            if(metadata.storesUpperCaseIdentifiers()) {
                column_name = column_name.toLowerCase();
            }

            String prec = rs.getString(7);
            String scal = rs.getString(9);
            int precision = -1;
            int scale = -1;
            if(prec != null) {
                precision = Integer.parseInt(prec);
                if(scal != null) {
                    scale = Integer.parseInt(scal);
                }
            }
            String type = rs.getString(6);
            if(prec != null && precision > 0) {
                type += "(" + precision;
                if(scal != null && scale > 0) {
                    type += "," + scale;
                }
                type += ")";
            }
            String def = rs.getString(13);
            IRubyObject _def;
            if(def == null) {
                _def = runtime.getNil();
            } else {
                if(isDerby && def.length() > 0 && def.charAt(0) == '\'') {
                    def = def.substring(1, def.length()-1);
                }
                _def = runtime.newString(def);
            }

            IRubyObject c = jdbcCol.callMethod(ctx,"new", new IRubyObject[]{recv.getInstanceVariable("@config"), runtime.newString(column_name),
                                                                            _def, runtime.newString(type), 
                                                                            runtime.newBoolean(!rs.getString(18).equals("NO"))});
            columns.add(c);

            IRubyObject tp = (IRubyObject)tps.fastARef(c.callMethod(ctx,"type"));
            if(tp != null && !tp.isNil() && tp.callMethod(ctx,"[]",runtime.newSymbol("limit")).isNil()) {
                c.callMethod(ctx,"limit=", runtime.getNil());
                if(!c.callMethod(ctx,"type").equals(runtime.newSymbol("decimal"))) {
                    c.callMethod(ctx,"precision=", runtime.getNil());
                }
            }
        }

        try {
            rs.close();
        } catch(Exception e) {}

        return runtime.newArray(columns);
    }

    public static IRubyObject primary_keys(IRubyObject recv, IRubyObject _table_name) throws SQLException {
        Connection c = (Connection)recv.dataGetStruct();
        DatabaseMetaData metadata = c.getMetaData();
        String table_name = _table_name.toString();
        if(metadata.storesUpperCaseIdentifiers()) {
            table_name = table_name.toUpperCase();
        } else if(metadata.storesLowerCaseIdentifiers()) {
            table_name = table_name.toLowerCase();
        }
        ResultSet result_set = metadata.getPrimaryKeys(null,null,table_name);
        List keyNames = new ArrayList();
        Ruby runtime = recv.getRuntime();
        while(result_set.next()) {
            keyNames.add(runtime.newString(result_set.getString(4).toLowerCase()));
        }
        
        try {
            result_set.close();
        } catch(Exception e) {}

        return runtime.newArray(keyNames);
    }

    public static IRubyObject execute_id_insert(IRubyObject recv, IRubyObject sql, IRubyObject id) throws SQLException {
        Connection c = (Connection)recv.dataGetStruct();
        PreparedStatement ps = c.prepareStatement(sql.toString());
        try {
            ps.setLong(1,RubyNumeric.fix2long(id));
            ps.executeUpdate();
        } finally {
            ps.close();
        }
        return id;
    }

    public static IRubyObject execute_update(IRubyObject recv, IRubyObject sql) throws SQLException {
        while(true) {
            Connection c = (Connection)recv.dataGetStruct();
            Statement stmt = null;
            try {
                stmt = c.createStatement();
                return recv.getRuntime().newFixnum(stmt.executeUpdate(sql.toString()));
            } catch(SQLException e) {
                if(c.isClosed()) {
                    recv.callMethod(recv.getRuntime().getCurrentContext(),"reconnect!");
                    continue;
                }
                throw e;
            } finally {
                if(null != stmt) {
                    try {
                        stmt.close();
                    } catch(Exception e) {}
                }
            }
        }
    }

    public static IRubyObject execute_query(IRubyObject recv, IRubyObject[] args) throws SQLException, IOException {
        IRubyObject sql = args[0];
        int maxrows = 0;
        if(args.length > 1) {
            maxrows = RubyNumeric.fix2int(args[1]);
        }
        while(true) {
            Connection c = (Connection)recv.dataGetStruct();
            Statement stmt = null;
            try {
                stmt = c.createStatement();
                stmt.setMaxRows(0);
                return unmarshal_result(recv, stmt.executeQuery(sql.toString()));
            } catch(SQLException e) {
                if(c.isClosed()) {
                    recv.callMethod(recv.getRuntime().getCurrentContext(),"reconnect!");
                    continue;
                }
                throw e;
            } finally {
                if(null != stmt) {
                    try {
                        stmt.close();
                    } catch(Exception e) {}
                }
            }
        }
    }

    public static IRubyObject execute_insert(IRubyObject recv, IRubyObject sql) throws SQLException {
        while(true) {
            Connection c = (Connection)recv.dataGetStruct();
            Statement stmt = null;
            try {
                stmt = c.createStatement();
                stmt.executeUpdate(sql.toString(), Statement.RETURN_GENERATED_KEYS);
                return unmarshal_id_result(recv.getRuntime(), stmt.getGeneratedKeys());
            } catch(SQLException e) {
                if(c.isClosed()) {
                    recv.callMethod(recv.getRuntime().getCurrentContext(),"reconnect!");
                    continue;
                }
                throw e;
            } finally {
                if(null != stmt) {
                    try {
                        stmt.close();
                    } catch(Exception e) {}
                }
            }
        }
    }

    public static IRubyObject unmarshal_result_downcase(IRubyObject recv, ResultSet rs) throws SQLException, IOException {
        Ruby runtime = recv.getRuntime();
        ResultSetMetaData metadata = rs.getMetaData();
        int col_count = metadata.getColumnCount();
        IRubyObject[] col_names = new IRubyObject[col_count];
        int[] col_types = new int[col_count];
        int[] col_scale = new int[col_count];

        for(int i=0;i<col_count;i++) {
            col_names[i] = runtime.newString(metadata.getColumnName(i+1).toLowerCase());
            col_types[i] = metadata.getColumnType(i+1);
            col_scale[i] = metadata.getScale(i+1);
        }

        List results = new ArrayList();
        while(rs.next()) {
            RubyHash row = RubyHash.newHash(runtime);
            for(int i=0;i<col_count;i++) {
                row.aset(col_names[i], jdbc_to_ruby(runtime, i+1, col_types[i], col_scale[i], rs));
            }
            results.add(row);
        }
 
        try {
            rs.close();
        } catch(Exception e) {}

        return runtime.newArray(results);
    }

    public static IRubyObject unmarshal_result(IRubyObject recv, ResultSet rs) throws SQLException, IOException {
        Ruby runtime = recv.getRuntime();
        ResultSetMetaData metadata = rs.getMetaData();
        boolean storesUpper = rs.getStatement().getConnection().getMetaData().storesUpperCaseIdentifiers();
        int col_count = metadata.getColumnCount();
        IRubyObject[] col_names = new IRubyObject[col_count];
        int[] col_types = new int[col_count];
        int[] col_scale = new int[col_count];

        for(int i=0;i<col_count;i++) {
            if(storesUpper) {
                col_names[i] = runtime.newString(metadata.getColumnName(i+1).toLowerCase());
            } else {
                col_names[i] = runtime.newString(metadata.getColumnName(i+1));
            }

            col_types[i] = metadata.getColumnType(i+1);
            col_scale[i] = metadata.getScale(i+1);
        }

        List results = new ArrayList();
        while(rs.next()) {
            RubyHash row = RubyHash.newHash(runtime);
            for(int i=0;i<col_count;i++) {
                row.aset(col_names[i], jdbc_to_ruby(runtime, i+1, col_types[i], col_scale[i], rs));
            }
            results.add(row);
        }
 
        try {
            rs.close();
        } catch(Exception e) {}

        return runtime.newArray(results);
    }

    public static IRubyObject unmarshal_result(IRubyObject recv, IRubyObject resultset, Block row_filter) throws SQLException, IOException {
        Ruby runtime = recv.getRuntime();
        ResultSet rs = intoResultSet(resultset);
        ResultSetMetaData metadata = rs.getMetaData();
        int col_count = metadata.getColumnCount();
        IRubyObject[] col_names = new IRubyObject[col_count];
        int[] col_types = new int[col_count];
        int[] col_scale = new int[col_count];

        for(int i=0;i<col_count;i++) {
            col_names[i] = runtime.newString(metadata.getColumnName(i+1));
            col_types[i] = metadata.getColumnType(i+1);
            col_scale[i] = metadata.getScale(i+1);
        }

        List results = new ArrayList();

        if(row_filter.isGiven()) {
            while(rs.next()) {
                if(row_filter.yield(runtime.getCurrentContext(),resultset).isTrue()) {
                    RubyHash row = RubyHash.newHash(runtime);
                    for(int i=0;i<col_count;i++) {
                        row.aset(col_names[i], jdbc_to_ruby(runtime, i+1, col_types[i], col_scale[i], rs));
                    }
                    results.add(row);
                }
            }
        } else {
            while(rs.next()) {
                RubyHash row = RubyHash.newHash(runtime);
                for(int i=0;i<col_count;i++) {
                    row.aset(col_names[i], jdbc_to_ruby(runtime, i+1, col_types[i], col_scale[i], rs));
                }
                results.add(row);
            }
        }
 
        try {
            rs.close();
        } catch(Exception e) {}

        return runtime.newArray(results);
    }

    private static IRubyObject jdbc_to_ruby(Ruby runtime, int row, int type, int scale, ResultSet rs) throws SQLException, IOException {
        int n;
        switch(type) {
        case Types.BINARY:
        case Types.BLOB:
        case Types.LONGVARBINARY:
        case Types.VARBINARY:
            InputStream is = rs.getBinaryStream(row);
            if(is == null || rs.wasNull()) {
                return runtime.getNil();
            }
            ByteList str = new ByteList(2048);
            byte[] buf = new byte[2048];
            while((n = is.read(buf)) != -1) {
                str.append(buf, 0, n);
            }
            is.close();
            return runtime.newString(str);
        case Types.LONGVARCHAR:
        case Types.CLOB:
            Reader rss = rs.getCharacterStream(row);
            if(rss == null || rs.wasNull()) {
                return runtime.getNil();
            }
            ByteList str2 = new ByteList(2048);
            char[] cuf = new char[2048];
            while((n = rss.read(cuf)) != -1) {
                str2.append(ByteList.plain(cuf), 0, n);
            }
            rss.close();
            return runtime.newString(str2);
        default:
            String vs = rs.getString(row);
            if(vs == null || rs.wasNull()) {
                return runtime.getNil();
            }
            return runtime.newString(vs);
        }
    }

    public static IRubyObject unmarshal_id_result(Ruby runtime, ResultSet rs) throws SQLException {
        try {
            if(rs.next()) {
                if(rs.getMetaData().getColumnCount() > 0) {
                    return runtime.newFixnum(rs.getLong(1));
                }
            }
            return runtime.getNil();
        } finally {
            try {
                rs.close();
            } catch(Exception e) {}
        }
    }

    private final static ByteList ZERO = new ByteList(new byte[]{'\\','0'});
    private final static ByteList NEWLINE = new ByteList(new byte[]{'\\','n'});
    private final static ByteList CARRIAGE = new ByteList(new byte[]{'\\','r'});
    private final static ByteList ZED = new ByteList(new byte[]{'\\','Z'});
    private final static ByteList DBL = new ByteList(new byte[]{'\\','"'});
    private final static ByteList SINGLE = new ByteList(new byte[]{'\\','\''});
    private final static ByteList ESCAPE = new ByteList(new byte[]{'\\','\\'});

    public static IRubyObject mysql_quote_string(IRubyObject recv, IRubyObject string) {
        boolean replacementFound = false;
        ByteList bl = ((RubyString) string).getByteList();
        
        for(int i = bl.begin; i < bl.begin + bl.realSize; i++) {
            ByteList rep = null;
            switch (bl.bytes[i]) {
            case 0: rep = ZERO; break;
            case '\n': rep = NEWLINE; break;
            case '\r': rep = CARRIAGE; break;
            case 26: rep = ZED; break;
            case '"': rep = DBL; break;
            case '\'': rep = SINGLE; break;
            case '\\': rep = ESCAPE; break;
            default: continue;
            }
            
            // On first replacement allocate a different bytelist so we don't manip original 
            if(!replacementFound) {
                bl = new ByteList(bl);
                replacementFound = true;
            }

            bl.replace(i, 1, rep);
            i+=1;
        }
        return recv.getRuntime().newStringShared(bl);
    }
}