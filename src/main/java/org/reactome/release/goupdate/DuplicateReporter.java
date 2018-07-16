package org.reactome.release.goupdate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;

/**
 * This class reports on duplicate GO Accessions.
 * A GO Accession is considered duplicated if more than one GO_MolecularFunction/GO_BiologicalProcess/GO_CellularComponent
 * has the same accession value. A direct SQL query is used to do this (should be faster than the API).
 * @author sshorser
 *
 */
public class DuplicateReporter
{
	private static String duplicateAccessionQuery = "select accession, count(db_id) as instance_count\n" + 
			"from (\n" + 
			"	select *\n" + 
			"	from GO_MolecularFunction\n" + 
			"	union \n" + 
			"	select *\n" + 
			"	from GO_BiologicalProcess\n" + 
			"	union\n" + 
			"	select *\n" + 
			"	from GO_CellularComponent) as subq\n" + 
			"group by accession\n" + 
			"having count(db_id) > 1;\n" ;

	private MySQLAdaptor adaptor;
	
	public DuplicateReporter(MySQLAdaptor adaptor)
	{
		this.adaptor = adaptor;
	}
	
	/**
	 * Gets the duplicated accessions.
	 * @return A map of accessions, and number of times they appear in the database.
	 * @throws SQLException
	 */
	public Map<String, Integer> getDuplicateAccessions() throws SQLException
	{
		Map<String, Integer> duplicateAccessions = new HashMap<String, Integer>();
		
		ResultSet results = this.adaptor.executeQuery(DuplicateReporter.duplicateAccessionQuery, null);
		
		while (results.next())
		{
			duplicateAccessions.put(results.getString("accession"), results.getInt("instance_count"));
		}
		
		return duplicateAccessions;
	}
	
	/**
	 * Gets the number of referrers for each instance of a duplicated accession.
	 * @param accession - The accession to look up.
	 * @return The DB_IDs of the duplicated accession mapping to the number of referrers of each one.
	 * @throws Exception 
	 */
	public Map<Long, Integer> getReferrerCountForAccession(String accession) throws Exception
	{
		Map<Long, Integer> referrerCounts = new HashMap<Long, Integer>();
		
		// We'll have to do this for BiologicalProcess, for MolecularFunction, and for CellularComponent
		for (String reactomeClass : Arrays.asList(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent))
		{
			
			@SuppressWarnings("unchecked")
			Collection<GKInstance> instances = (Collection<GKInstance>) this.adaptor.fetchInstanceByAttribute(reactomeClass, ReactomeJavaConstants.accession, "=", accession);
			if (instances != null)
			{
				for (GKInstance i : instances)
				{
					for (GKSchemaAttribute referrerAttrib : (Set<GKSchemaAttribute>)i.getSchemClass().getReferers())
					{
						@SuppressWarnings("unchecked")
						Collection<GKInstance> referrers = (Collection<GKInstance>) i.getReferers(referrerAttrib);
						if (referrerCounts.containsKey(i.getDBID()))
						{
							referrerCounts.put(i.getDBID(), referrerCounts.get(i.getDBID()) + referrers.size());
						}
						else
						{
							referrerCounts.put(i.getDBID(), referrers.size());
						}
					}
				}
			}
		}
		return referrerCounts;
	}
	
}
