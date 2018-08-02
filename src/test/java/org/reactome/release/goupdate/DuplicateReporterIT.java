/**
 * 
 */
package org.reactome.release.goupdate;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for DuplicatesReporter
 * @author sshorser
 *
 */
public class DuplicateReporterIT
{
	private static MySQLAdaptor adaptor;
	
	@Before
	public void setup() throws SQLException
	{
		adaptor = new MySQLAdaptor("localhost", "gk_central_Perl_GO_Update", "root", "root", 3306);
	}
	
	
	@Test
	public void testGetDuplicateAccessions() throws SQLException
	{
		DuplicateReporter dupeReporter = new DuplicateReporter(adaptor);
		
		Map<String, Integer> duplicates = dupeReporter.getDuplicateAccessions();
		
		assertNotNull(duplicates);
		
		assertTrue(duplicates.size() > 0);
		
		for (String accession : duplicates.keySet())
		{
			assertNotNull(duplicates.get(accession));
			// Values should always be > 1, since these are duplicates!
			assertTrue(duplicates.get(accession) > 1);
			System.out.println("Accession " + accession +" is duplicated " + duplicates.get(accession) + " times.");
		}
		
	}
	
	@Test
	public void testGetDuplicateReferrers() throws Exception
	{
		int totalDuplicatesCount = 0;
		int instancesWithSignificantReferrers = 0;
		
		DuplicateReporter dupeReporter = new DuplicateReporter(adaptor);
		
		Map<String, Integer> duplicates = dupeReporter.getDuplicateAccessions();
		
		assertNotNull(duplicates);
		
		assertTrue(duplicates.size() > 0);
		
		for (String accession : duplicates.keySet())
		{
			assertNotNull(duplicates.get(accession));
			totalDuplicatesCount = duplicates.keySet().size();
			// Values should always be > 1, since these are duplicates!
			assertTrue(duplicates.get(accession) > 1);
			System.out.println("Accession " + accession +" is duplicated " + duplicates.get(accession) + " times.");
			
			Map <Long,Integer> refCounts = dupeReporter.getReferrerCountForAccession(accession, ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.Compartment);
			if (refCounts != null && refCounts.size() > 0)
			{
				for (Long dbId : refCounts.keySet())
				{
					System.out.println("\tAccession instance with DB_ID "+dbId + " has "+refCounts.get(dbId) + " significant referrers");
					if (refCounts.get(dbId) > 0)
					{
						instancesWithSignificantReferrers ++;
						GKInstance inst = adaptor.fetchInstance(dbId);
						@SuppressWarnings("unchecked")
						Collection<GKSchemaAttribute> refAttribs = (Collection<GKSchemaAttribute>) inst.getSchemClass().getReferers();
						for (GKSchemaAttribute refAtt : refAttribs)
						{
							@SuppressWarnings("unchecked")
							Collection<GKInstance> refs = (Collection<GKInstance>)inst.getReferers(refAtt);
							if (refs != null && refs.size() > 0)
							{
								for (GKInstance ref : refs)
								{
									if (!Arrays.asList(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.Compartment).contains(ref.getSchemClass().getName()))
									{
										System.out.println("\t\tvia "+refAtt.getName()+":\t"+ref.toString());
									}
								}
							}
						}
					}
				}
				System.out.print("\n");
			}
//			else
//			{
//				System.out.println("Accession "+accession+ " has no instances with referrers!");
//			}
			
		}
		System.out.println("\nSummary:\nTotal number of duplicated accessions: "+totalDuplicatesCount);
		System.out.println("Number of instances with significant (non-GO Term) referrers: "+instancesWithSignificantReferrers);

	}
}
