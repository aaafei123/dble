package io.mycat.meta.table;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLColumnConstraint;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLNotNullConstraint;
import com.alibaba.druid.sql.ast.statement.SQLNullConstraint;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlPrimaryKey;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlUnique;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlTableIndex;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlOutputVisitor;
import com.alibaba.druid.sql.parser.SQLStatementParser;

import io.mycat.MycatServer;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.config.model.TableConfig;
import io.mycat.meta.protocol.MyCatMeta.ColumnMeta;
import io.mycat.meta.protocol.MyCatMeta.IndexMeta;
import io.mycat.meta.protocol.MyCatMeta.TableMeta;
import io.mycat.sqlengine.OneRawSQLQueryResultHandler;
import io.mycat.sqlengine.SQLJob;
import io.mycat.sqlengine.SQLQueryResult;
import io.mycat.sqlengine.SQLQueryResultListener;

public abstract class AbstractTableMetaHandler {
	protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractTableMetaHandler.class);
    private static final String[] MYSQL_SHOW_CREATE_TABLE_COLMS = new String[]{
            "Table",
            "Create Table"};
    private static final String sqlPrefix = "show create table ";

	
	private TableConfig tbConfig;
	private AtomicInteger nodesNumber;
	protected String schema;
	public AbstractTableMetaHandler( String schema,  TableConfig tbConfig){
		this.tbConfig = tbConfig;
		this.nodesNumber = new AtomicInteger(tbConfig.getDataNodes().size());
		this.schema = schema;
	}
	public void execute(){
		for (String dataNode : tbConfig.getDataNodes()) {
			try {
				tbConfig.getReentrantReadWriteLock().writeLock().lock();
				ConcurrentHashMap<String, List<String>> map = new ConcurrentHashMap<>();
				tbConfig.setDataNodeTableStructureSQLMap(map);
			} finally {
				tbConfig.getReentrantReadWriteLock().writeLock().unlock();
			}
			OneRawSQLQueryResultHandler resultHandler = new OneRawSQLQueryResultHandler(MYSQL_SHOW_CREATE_TABLE_COLMS, new MySQLTableStructureListener(dataNode,  System.currentTimeMillis()));
			resultHandler.setMark("Table Structure");
			PhysicalDBNode dn = MycatServer.getInstance().getConfig().getDataNodes().get(dataNode);
			SQLJob sqlJob = new SQLJob(sqlPrefix + tbConfig.getName(), dn.getDatabase(), resultHandler, dn.getDbPool().getSource());
			sqlJob.run();
		}
	}
	protected abstract void countdown();
	protected abstract void handlerTable(TableMeta tableMeta);
	private class MySQLTableStructureListener implements SQLQueryResultListener<SQLQueryResult<Map<String, String>>> {
		private String dataNode;
		private long version;
		public MySQLTableStructureListener(String dataNode, long version) {
			this.dataNode = dataNode;
			this.version = version;
		}

        @Override
        public void onResult(SQLQueryResult<Map<String, String>> result) {
            try {
            	tbConfig.getReentrantReadWriteLock().writeLock().lock();
                if (!result.isSuccess()) { 
                	//not thread safe
                	LOGGER.warn("Can't get table " + tbConfig.getName() + "'s config from DataNode:" + dataNode + "! Maybe the table is not initialized!"); 
                	if (nodesNumber.decrementAndGet() == 0) {
                		countdown();
                	}
                    return;
                }
                String currentSql = result.getResult().get(MYSQL_SHOW_CREATE_TABLE_COLMS[1]);
                Map<String, List<String>> dataNodeTableStructureSQLMap = tbConfig.getDataNodeTableStructureSQLMap();
                if (dataNodeTableStructureSQLMap.containsKey(currentSql)) {
                    List<String> dataNodeList = dataNodeTableStructureSQLMap.get(currentSql);
                    dataNodeList.add(dataNode);
                } else {
                    List<String> dataNodeList = new LinkedList<>();
                    dataNodeList.add(dataNode);
                    dataNodeTableStructureSQLMap.put(currentSql,dataNodeList);
                }

				if (nodesNumber.decrementAndGet() == 0) {
					TableMeta tableMeta = null;
					if (dataNodeTableStructureSQLMap.size() > 1) {
						// Through the SQL is different, the table Structure may still same.
						// for example: autoIncreament number
						Set<TableMeta> tableMetas = new HashSet<TableMeta>(); 
						for (String sql : dataNodeTableStructureSQLMap.keySet()) {
							tableMeta = initTableMeta(tbConfig.getName(), sql, version);
							tableMetas.add(tableMeta);
						}
						if (tableMetas.size() > 1) {
							consistentWarning(dataNodeTableStructureSQLMap);
						}
						tableMetas.clear();
					} else {
						tableMeta = initTableMeta(tbConfig.getName(), currentSql, version);
					}
					handlerTable(tableMeta);
					countdown();
				}
            } finally {
            	tbConfig.getReentrantReadWriteLock().writeLock().unlock();
            }
        }

        private void consistentWarning(Map<String, List<String>> dataNodeTableStructureSQLMap){
        	LOGGER.warn("Table [" + tbConfig.getName() + "] structure are not consistent!");
            LOGGER.warn("Currently detected: ");
            for(String sql : dataNodeTableStructureSQLMap.keySet()){
                StringBuilder stringBuilder = new StringBuilder();
                for(String dn : dataNodeTableStructureSQLMap.get(sql)){
                    stringBuilder.append("DataNode:[").append(dn).append("]");
                }
                stringBuilder.append(":").append(sql);
                LOGGER.warn(stringBuilder.toString());
            }
        }
		private TableMeta initTableMeta(String table, String sql, long timeStamp) {
			TableMeta.Builder tmBuilder = TableMeta.newBuilder();
			tmBuilder.setTableName(tbConfig.getName());
			tmBuilder.setVersion(timeStamp);
			SQLStatementParser parser = new MySqlStatementParser(sql);
			SQLCreateTableStatement createStment = parser.parseCreateTable();
			for (SQLTableElement tableElement : createStment.getTableElementList()) {
				if (tableElement instanceof SQLColumnDefinition) {
					addColumnMeta(tmBuilder, table, (SQLColumnDefinition) tableElement);
				} else if (tableElement instanceof MySqlPrimaryKey) {
					MySqlPrimaryKey primaryKey = (MySqlPrimaryKey) tableElement;
					tmBuilder.setPrimary(makeIndexMeta("PRIMARY",  "PRI", primaryKey.getColumns()));
				} else if (tableElement instanceof MySqlUnique) {
					MySqlUnique unique = (MySqlUnique) tableElement;
					tmBuilder.addUniIndex(makeIndexMeta(unique.getIndexName().getSimpleName(), "UNI", unique.getColumns()));
				} else if (tableElement instanceof MySqlTableIndex) {
					MySqlTableIndex index = (MySqlTableIndex) tableElement;
					tmBuilder.addIndex(makeIndexMeta(index.getName().getSimpleName(), "MUL", index.getColumns()));
				} else {
					// ignore
				}
			}
			return tmBuilder.build();
		}

		private IndexMeta makeIndexMeta(String index, String indexType, List<SQLExpr> columnExprs) {
			IndexMeta.Builder indexBuilder = IndexMeta.newBuilder();
			indexBuilder.setName(index);
			indexBuilder.setType(indexType);
			for (int i = 0; i < columnExprs.size(); i++) {
				SQLIdentifierExpr column = (SQLIdentifierExpr) columnExprs.get(i);
				indexBuilder.addColumns(column.getName()); 
			}
			return indexBuilder.build();
		}

		private void addColumnMeta(TableMeta.Builder tmBuilder, String table, SQLColumnDefinition column) {
			ColumnMeta.Builder cmBuilder = ColumnMeta.newBuilder();
			cmBuilder.setName(column.getName().getSimpleName());
			cmBuilder.setDataType(column.getDataType().getName());
			for (SQLColumnConstraint constraint : column.getConstraints()) {
				if (constraint instanceof SQLNotNullConstraint) {
					cmBuilder.setCanNull(false);
				} else if (constraint instanceof SQLNullConstraint) {
					cmBuilder.setCanNull(true);
				} else {
					// SQLColumnPrimaryKey ,SQLColumnUniqueKey will not happen in "show create table ..", ignore
				}
			}
			if (column.getDefaultExpr() != null) {
				StringBuilder builder = new StringBuilder();
				MySqlOutputVisitor visitor = new MySqlOutputVisitor(builder);
				column.getDefaultExpr().accept(visitor);
				cmBuilder.setSdefault(builder.toString());
			}
			if (column.isAutoIncrement()) {
				cmBuilder.setAutoIncre(true);
				tmBuilder.setAiColPos(tmBuilder.getColumnsCount());
			}
			tmBuilder.addColumns(cmBuilder.build());
		}
    }
}