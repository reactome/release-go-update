package org.reactome.release.goupdate;

import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.gk.schema.SchemaClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({DuplicateReporter.class, DuplicateCleaner.class, MySQLAdaptor.class})
@MockitoSettings(strictness = Strictness.WARN)
@PowerMockIgnore({"javax.management.*","javax.script.*"})
public class DuplicateCleanerTest
{

	DuplicateCleaner duplicateCleaner;
	
	@Mock
	DuplicateReporter mockReporter;
	
	@Mock
	MySQLAdaptor mockAdaptor;
	
	@Mock
	GKInstance mockInstance;
	
	@Mock
	GKInstance mockModifiedInstance;
	
	@Mock
	GKInstance mockReferrer;
	
	@Mock
	GKInstance mockReferrer2;
	
	@Mock
	SchemaClass mockSchemaClass;
	
	@Mock
	GKSchemaAttribute mockAttribute;
	
	@Before
	public void setUp() throws Exception
	{
		MockitoAnnotations.initMocks(this);
		PowerMockito.whenNew(MySQLAdaptor.class).withArguments(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyInt() ).thenReturn(mockAdaptor);
		PowerMockito.whenNew(MySQLAdaptor.class).withAnyArguments().thenReturn(mockAdaptor);
	}

	@Test
	public void testExecuteStep() throws Exception
	{
		Properties props = new Properties();
		Mockito.when(mockReporter.getReferrerCountForAccession(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(null);
		PowerMockito.whenNew(DuplicateReporter.class).withAnyArguments().thenReturn(mockReporter);
		Map<String, Integer> duplicatesMap = new HashMap<>();
		Map<Long, Integer> referrerMap = new HashMap<>();
		// Very simple: we will test with a single duplicate
		duplicatesMap.put("TEST-1", 2);
		duplicatesMap.put("TEST-2", 1);
		long referrerDBID = 123456L;
		referrerMap.put(referrerDBID, 1);
		
		Mockito.when(mockReferrer.getDBID()).thenReturn(referrerDBID);
		Mockito.when(mockAdaptor.fetchInstance(referrerDBID)).thenReturn(mockReferrer);
		Mockito.when(mockAttribute.getName()).thenReturn("mock attribute");
		Mockito.when(mockSchemaClass.getReferers()).thenReturn(Arrays.asList(mockAttribute));
		Mockito.when(mockReferrer.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(mockReferrer2.getSchemClass()).thenReturn(mockSchemaClass);
		Mockito.when(mockReferrer.getReferers(mockAttribute)).thenReturn(Arrays.asList(mockReferrer2));
		
		
		Mockito.when(mockReporter.getDuplicateAccessions()).thenReturn(duplicatesMap);
		Mockito.when(mockReporter.getReferrerCountForAccession(ArgumentMatchers.anyString(), ArgumentMatchers.any())).thenReturn(new HashMap<Long, Integer>()).thenReturn(referrerMap);
		Mockito.when(mockModifiedInstance.getAttributeValue(ReactomeJavaConstants.dateTime)).thenReturn("2019-01-01 00:00:00.0");
		Mockito.when(mockInstance.getAttributeValue(ReactomeJavaConstants.modified)).thenReturn(mockModifiedInstance);
		Mockito.when(mockAdaptor.fetchInstanceByAttribute(ReactomeJavaConstants.GO_BiologicalProcess,  ReactomeJavaConstants.accession, "=", "TEST-1") ).thenReturn(Arrays.asList(mockInstance));
		
		duplicateCleaner = new DuplicateCleaner();
		duplicateCleaner.executeStep(props);
		
		// If we get here without crashing, the test passed.
		assertTrue(true);
	}

	
	@Test
	public void testExecuteStepNoDuplicates() throws Exception
	{
		PowerMockito.whenNew(DuplicateReporter.class).withAnyArguments().thenReturn(mockReporter);
		Map<String, Integer> duplicatesMap = new HashMap<>();
		Mockito.when(mockReporter.getDuplicateAccessions()).thenReturn(duplicatesMap);
		Properties props = new Properties();
		duplicateCleaner = new DuplicateCleaner();
		duplicateCleaner.executeStep(props);
		
		// If we get here without crashing, the test passed.
		assertTrue(true);
	}
}
