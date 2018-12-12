package org.reactome.release.goupdate;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.reactome.release.common.ReleaseStep;

/**
 * Stand-alone class with main method that can be used to clean up duplicate GO accessions.
 * @author sshorser
 *
 */
public class DuplicateCleaner extends ReleaseStep
{
	private static final Logger logger = LogManager.getLogger();

	public static void main(String[] args)
	{
		String pathToResources = "src/main/resources/go-update.properties";
		if (args.length > 0)
		{
			pathToResources = args[0];
		}
		try
		{
			Properties props = new Properties();
			props.load(new FileInputStream(pathToResources));
			DuplicateCleaner step = new DuplicateCleaner();
			step.executeStep(props);
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}

	}

	@Override
	public void executeStep(Properties props) throws Exception
	{
		MySQLAdaptor adaptor = DuplicateCleaner.getMySQLAdaptorFromProperties(props);
		int totalDuplicatesCount = 0;
		int instancesWithSignificantReferrers = 0;
		
		DuplicateReporter dupeReporter = new DuplicateReporter(adaptor);
		Set<Long> dbIDsToDelete = new HashSet<Long>();

		Map<String, Integer> duplicates = dupeReporter.getDuplicateAccessions();
		
		// loop through all duplicated accessions.
		for (String accession : duplicates.keySet())
		{
			totalDuplicatesCount = duplicates.keySet().size();
			logger.info("Accession {} is duplicated {} times.", accession, duplicates.get(accession));
			List<Long> dbIDsWithNoReferrers = new ArrayList<Long>();
			Map <Long,Integer> refCounts = dupeReporter.getReferrerCountForAccession(accession, ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.Compartment);
			// if there are referrers...
			if (refCounts != null && refCounts.size() > 0)
			{
				// for each DB ID in referrer counts
				for (Long dbId : refCounts.keySet())
				{
					// if there are referrers.
					if (refCounts.get(dbId) > 0)
					{
						logger.info("\tAccession instance with DB_ID {} has {} significant referrers", dbId, refCounts.get(dbId));

						// update count.
						instancesWithSignificantReferrers ++;
						GKInstance inst = adaptor.fetchInstance(dbId);
						@SuppressWarnings("unchecked")
						Collection<GKSchemaAttribute> refAttribs = (Collection<GKSchemaAttribute>) inst.getSchemClass().getReferers();
						// check each atttribute
						for (GKSchemaAttribute refAtt : refAttribs)
						{
							@SuppressWarnings("unchecked")
							Collection<GKInstance> refs = (Collection<GKInstance>) inst.getReferers(refAtt);
							if (refs != null && refs.size() > 0)
							{
								for (GKInstance ref : refs)
								{
									if (!Arrays.asList(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent, ReactomeJavaConstants.Compartment).contains(ref.getSchemClass().getName()))
									{
										logger.info("\t\tvia {}:\t{}", refAtt.getName(), ref.toString());
									}
								}
							}
						}
					}
					// if there are NO referrers, add the db_id to the list of DB IDs without referrers.
					else
					{
						logger.debug("DB ID {} for accession {} has no significant referrers, and will be deleted.", dbId, accession);
						dbIDsWithNoReferrers.add(dbId);
					}
				}
			}
			// update list of things to delete with IDs that have no referrers.
			dbIDsToDelete.addAll(dbIDsWithNoReferrers);
			// If no DB IDs have significant referrers, we should delete ALL DB_IDs for the accession, except for the newest one.
			if (dbIDsWithNoReferrers.size() == refCounts.keySet().size())
			{
				GKInstance newestInstance = null;
				LocalDateTime newestDate = null;
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");
				// Need to try all classes since we don't know which class a GO Accession might be.
				for (String reactomeClass : Arrays.asList(ReactomeJavaConstants.GO_BiologicalProcess, ReactomeJavaConstants.GO_MolecularFunction, ReactomeJavaConstants.GO_CellularComponent))
				{
					GKInstance modifiedInstance = null;
					
					@SuppressWarnings("unchecked")
					Collection<GKInstance> instances = (Collection<GKInstance>) adaptor.fetchInstanceByAttribute(reactomeClass, ReactomeJavaConstants.accession, "=", accession);
					// For each instance for this accession, try to find the newest one so that it won't be deleted.
					if (instances != null && instances.size() > 0)
					{
						for (GKInstance instance : instances)
						{
							dbIDsToDelete.add(instance.getDBID());
							LocalDateTime localDate = null;
							modifiedInstance = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.modified);
							
							if (modifiedInstance != null)
							{
								localDate = LocalDateTime.parse((CharSequence) modifiedInstance.getAttributeValue(ReactomeJavaConstants.dateTime), formatter);
							}
							else
							{
								modifiedInstance = (GKInstance) instance.getAttributeValue(ReactomeJavaConstants.created);
								localDate = LocalDateTime.parse((CharSequence) modifiedInstance.getAttributeValue(ReactomeJavaConstants.dateTime), formatter);
							}

							if (newestInstance == null)
							{
								newestInstance = instance;
								newestDate = localDate;
								
							}
							if (localDate != null && newestDate != null && localDate.compareTo(newestDate) > 0)
							{
								newestDate = localDate;
								newestInstance = instance;
							}
						}
					}
				}
				logger.debug("Newest instance for accession {} is {} - this instance will NOT be deleted.", accession, newestInstance.toString());
				// REMOVE the ID of the newest instance from the list of things to delete (it would have been automatically added if the "newest" instance
				// had 0 referrers).
				dbIDsToDelete.remove(newestInstance.getDBID());
			}
		}
		logger.info("\n\nSummary:\nTotal number of duplicated accessions: {} \n"
				+ "Number of instances with significant (non-GO Term) referrers: {}\n", totalDuplicatesCount, instancesWithSignificantReferrers);
		logger.info("{} IDs will be deleted.", dbIDsToDelete.size());
		adaptor.startTransaction();
		for (Long dbID : dbIDsToDelete)
		{
			GKInstance instance = adaptor.fetchInstance(dbID);
			logger.info("DB ID {} (for accession {}) will be deleted", dbID, instance.getAttributeValue(ReactomeJavaConstants.accession));
			if (!this.testMode)
			{
				adaptor.deleteByDBID(dbID);
			}
		}
		adaptor.commit();
	}
}
