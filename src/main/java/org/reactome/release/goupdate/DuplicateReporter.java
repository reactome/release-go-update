package org.reactome.release.goupdate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
		Map<String, Integer> duplicateAccessions = new HashMap<>();
		
		try(ResultSet results = this.adaptor.executeQuery(DuplicateReporter.duplicateAccessionQuery, null);)
		{
			while (results.next())
			{
				duplicateAccessions.put(results.getString("accession"), results.getInt("instance_count"));
			}
		}
		return duplicateAccessions;
	}
	
	/**
	 * Gets the number of referrers for each instance of a duplicated accession.
	 * @param accession - The accession to look up.
	 * @param classesToIgnore - A list of class names to ignore, when looking for referrers. Any referrer whose Schema Class is in classes to ignore will not be considered as a referrer, and will not be added to the count.
	 * @return The DB_IDs of the duplicated accession mapping to the number of referrers of each one.
	 * @throws Exception 
	 */
	
	public Map<Long, Integer> getReferrerCountForAccession(String accession, String ... classesToIgnore) throws Exception
	{
		Map<Long, Integer> referrerCounts = new HashMap<>();
		
		// We'll have to do this for BiologicalProcess, for MolecularFunction, and for CellularComponent
		for (String reactomeClass : Arrays.asList(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent))
		{
			Collection<GKInstance> instances;
			if (accession == null)
			{
				instances = (Collection<GKInstance>) this.adaptor.fetchInstanceByAttribute(reactomeClass, ReactomeJavaConstants.accession, "IS NULL", accession);
			}
			else
			{
				instances = (Collection<GKInstance>) this.adaptor.fetchInstanceByAttribute(reactomeClass, ReactomeJavaConstants.accession, "=", accession);
			}
			if (instances != null)
			{
				for (GKInstance i : instances)
				{
					long dbid = i.getDBID();
					int refCount = getReferrerCountforInstance(i, classesToIgnore);
					referrerCounts.put(dbid, refCount);
				}
			}
		}
		return referrerCounts;
	}
	
	// TODO: Move this function to release-common-lib, maybe. It's a generic utility function, could be useful somewhere else.
	/**
	 * Gets the number of referrers for an instance. That is, it returns how many objects refer to <code>instance</code>.
	 * @param instance - the object to query about.
	 * @param classesToIgnore - a list of class names to ignore. Referrers whose SchemaClass name match a name in this list will *not* be included in the final count.
	 * @return
	 * @throws Exception
	 */
	public int getReferrerCountforInstance(GKInstance instance, String ... classesToIgnore) throws Exception
	{
		int refCount = 0;
		
		for (GKSchemaAttribute referrerAttrib : (Set<GKSchemaAttribute>) instance.getSchemClass().getReferers())
		{
			@SuppressWarnings("unchecked")
			Collection<GKInstance> referrers = (Collection<GKInstance>) instance.getReferers(referrerAttrib);
			if (classesToIgnore != null && classesToIgnore.length > 0)
			{
				// filter the referrers: we will collect all Referrers into a new list, IF their Class is not in the list of classes to ignore.
				referrers = referrers.stream().filter(referrer -> !(Arrays.asList(classesToIgnore)).contains(referrer.getSchemClass().getName()))
											.collect(Collectors.toList());
			}
			refCount += referrers.size();
		}
		return refCount;
	}
}
