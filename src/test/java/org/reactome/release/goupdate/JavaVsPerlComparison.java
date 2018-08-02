package org.reactome.release.goupdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaAttribute;
import org.reactome.util.compare.DBObjectComparer;

public class JavaVsPerlComparison {

	public static void main(String[] args) throws Exception
	{
		MySQLAdaptor javaUpdatedDB = new MySQLAdaptor("localhost", "gk_central_Java_GO_Update", "root", "root");
		MySQLAdaptor perlUpdatedDB = new MySQLAdaptor("localhost", "gk_central_Perl_GO_Update", "root", "root");
		int diffCount = 0;
		int sameCount = 0;
		
		int regulationDiffCount = 0;
		int regulationSameCount = 0;
		
		int peDiffCount = 0;
		int peSameCount = 0;
		
		int catActDiffCount = 0;
		int catActSameCount = 0;
		
		
		@SuppressWarnings("unchecked")
		Set<GKInstance> javaUpdatedGOBiologicalProcesses = (Set<GKInstance>) javaUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_BiologicalProcess);
		@SuppressWarnings("unchecked")
		Set<GKInstance> javaUpdatedGOMolecularFunctions = (Set<GKInstance>) javaUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_MolecularFunction);
		@SuppressWarnings("unchecked")
		Set<GKInstance> javaUpdatedGOCellularComponents = (Set<GKInstance>) javaUpdatedDB.fetchInstancesByClass(ReactomeJavaConstants.GO_CellularComponent);
		
		List<GKInstance> listOfAllJavaGOThings = new ArrayList<GKInstance>();
		listOfAllJavaGOThings.addAll(javaUpdatedGOCellularComponents);
		listOfAllJavaGOThings.addAll(javaUpdatedGOMolecularFunctions);
		listOfAllJavaGOThings.addAll(javaUpdatedGOBiologicalProcesses);
		
		Comparator<? super GKInstance> dbIdComparator = new Comparator<GKInstance>()
		{
			@Override
			public int compare(GKInstance o1, GKInstance o2)
			{
				//return InstanceUtilities.compareInstances(o1, o2);
				return o1.getDBID().compareTo(o2.getDBID());
			}
			
		};
		
		listOfAllJavaGOThings.sort(dbIdComparator);
		StringBuilder mainSB = new StringBuilder();
		for (GKInstance javaGoInst : listOfAllJavaGOThings)
		{
			String accession = (String) javaGoInst.getAttributeValue(ReactomeJavaConstants.accession);
			@SuppressWarnings("unchecked")
			Collection<GKInstance> perlGoInsts = (Collection<GKInstance>) perlUpdatedDB.fetchInstanceByAttribute(javaGoInst.getSchemClass().getName(), ReactomeJavaConstants.accession, "=", accession);
			for (GKInstance perlInst : perlGoInsts)
			{
				//if (perlInst != null)
				{
					StringBuilder sb = new StringBuilder();
					int i = DBObjectComparer.compareInstances(javaGoInst, perlInst, sb, 0);
					if (i> 0)
					{
						diffCount ++;
						mainSB.append("\n***\nFor Java-updated instance \""+javaGoInst.toString()+"\" with accession \""+accession+"\" there are " + i + " differences:\n"+sb.toString());
					}
					else
					{
						sameCount ++;
					}
					Predicate<? super SchemaAttribute> predicate = p-> {
						return p.getName().equals("name")
								|| p.getName().equals("_displayName");
						};
					
					// Now, we need to compare referrers, since they might have display name changes. Want to make sure we capture those correctly.
					if (javaGoInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_BiologicalProcess))
					{
						StringBuilder sb1 = new StringBuilder();

						@SuppressWarnings("unchecked")
						List<GKInstance> javaList = ((List<GKInstance>) javaGoInst.getReferers(ReactomeJavaConstants.goBiologicalProcess));
						@SuppressWarnings("unchecked")
						List<GKInstance> perlList = ((List<GKInstance>) perlInst.getReferers(ReactomeJavaConstants.goBiologicalProcess));
						if (javaList != null && perlList != null)
						{
							javaList = javaList.stream().sorted(dbIdComparator).collect(Collectors.toList());
							perlList = perlList.stream().sorted(dbIdComparator).collect(Collectors.toList());

							GKInstance javaReferringRegulation =  javaList.get(0);
							GKInstance perlReferringRegulation = perlList.get(0);

							int numDiffs = DBObjectComparer.compareInstances(javaReferringRegulation, perlReferringRegulation, sb1, 0, predicate);
							if (numDiffs > 0)
							{
								regulationDiffCount ++;
								mainSB.append("\n***\nFor Java-updated referring Regulation instance \""+javaReferringRegulation.toString()+"\" there are " + numDiffs + " differences:\n"+sb1.toString());
							}
							else
							{
								regulationSameCount ++;
							}
						}
					}
					else if (javaGoInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_CellularComponent))
					{
						StringBuilder sb1 = new StringBuilder();
						@SuppressWarnings("unchecked")
						List<GKInstance> javaList = ((List<GKInstance>) javaGoInst.getReferers(ReactomeJavaConstants.goCellularComponent));
						@SuppressWarnings("unchecked")
						List<GKInstance> perlList = ((List<GKInstance>) perlInst.getReferers(ReactomeJavaConstants.goCellularComponent));

						if (javaList!=null && perlList!=null)
						{
							javaList = javaList.stream().sorted(dbIdComparator).collect(Collectors.toList());
							perlList = perlList.stream().sorted(dbIdComparator).collect(Collectors.toList());
							GKInstance javaReferringPE = javaList.get(0);
							GKInstance perlReferringPE = perlList.get(0);

							int numDiffs = DBObjectComparer.compareInstances(javaReferringPE, perlReferringPE, sb1, 0, predicate);
							if (numDiffs > 0)
							{
								peDiffCount ++;
								mainSB.append("\n***\nFor Java-updated referring PhysicalEntity instance \""+javaReferringPE.toString()+"\" there are " + numDiffs + " differences:\n"+sb1.toString());
							}
							else
							{
								peSameCount ++;
							}
						}
					}
					else if (javaGoInst.getSchemClass().getName().equals(ReactomeJavaConstants.GO_MolecularFunction))
					{
						StringBuilder sb1 = new StringBuilder();
						@SuppressWarnings("unchecked")
						List<GKInstance> javaList = ((List<GKInstance>) javaGoInst.getReferers(ReactomeJavaConstants.activity));
						@SuppressWarnings("unchecked")
						List<GKInstance> perlList = ((List<GKInstance>) perlInst.getReferers(ReactomeJavaConstants.activity));

						if (javaList!=null && perlList!=null)
						{
							javaList = javaList.stream().sorted(dbIdComparator).collect(Collectors.toList());
							perlList = perlList.stream().sorted(dbIdComparator).collect(Collectors.toList());

							GKInstance javaReferringCatalystActivity =  javaList.get(0);
							GKInstance perlReferringCatalystActivity =  perlList.get(0);

							int numDiffs = DBObjectComparer.compareInstances(javaReferringCatalystActivity, perlReferringCatalystActivity, sb1, 0, predicate);
							if (numDiffs > 0)
							{
								catActDiffCount ++;
								mainSB.append("\n***\nFor Java-updated referring CatalystActivity instance \""+javaReferringCatalystActivity.toString()+"\" there are " + numDiffs + " differences:\n"+sb1.toString());
							}
							else
							{
								catActSameCount ++;
							}
						}
					}
				}
			}
			
		}
		System.out.println(mainSB.toString());
		System.out.println(sameCount+" instances were the same");
		System.out.println(diffCount+" instances were different");
	
		System.out.println("\nFor instances that *refer* to GO instances...\n");
		System.out.println(peDiffCount + " PhysicalEntities had differences.");
		System.out.println(peSameCount + " PhysicalEntities were the same.\n");
		System.out.println(regulationDiffCount + " Regulations had differences.");
		System.out.println(regulationSameCount + " Regulations were the same.\n");
		System.out.println(catActDiffCount + " CatalystActivities had differences.");
		System.out.println(catActSameCount + " CatalystActivities were the same.\n");
	}

}
