/**
 * 
 */
package org.reactome.release.goupdate;



import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.util.Map;

import org.gk.persistence.MySQLAdaptor;
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
		adaptor = new MySQLAdaptor("localhost", "gk_central_Java_GO_Update", "root", "root", 3306);
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
			
			Map <Long,Integer> refCounts = dupeReporter.getReferrerCountForAccession(accession);
			if (refCounts != null && refCounts.size() > 0)
			{
				for (Long dbId : refCounts.keySet())
				{
					System.out.println("Accession instance with DB_ID "+dbId + " has "+refCounts.get(dbId) + " referrers");
				}
			}
			else
			{
				System.out.println("Accession "+accession+ " has no instances with referrers!");
			}
			
		}
	}
}
