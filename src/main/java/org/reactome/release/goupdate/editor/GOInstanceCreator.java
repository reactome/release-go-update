package org.reactome.release.goupdate.editor;

import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaClass;
import org.reactome.release.goupdate.GoUpdateInstanceEditUtils;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;

import java.util.List;
import java.util.Set;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;

public class GOInstanceCreator {
    private final MySQLAdaptor adaptor;

    public GOInstanceCreator(MySQLAdaptor adaptor) {
        this.adaptor = adaptor;
    }

    public GKInstance createNewGOInstance(GoTerm goTerm) throws Exception {
        SchemaClass schemaClass = adaptor.getSchema().getClassByName(goTerm.getNamespace().getReactomeName());
        GKInstance newGOInstance = new GKInstance(schemaClass);

        newGOInstance.setAttributeValue(ReactomeJavaConstants.accession, goTerm.getId());
        newGOInstance.setAttributeValue(ReactomeJavaConstants.name, goTerm.getName());
        newGOInstance.setAttributeValue(ReactomeJavaConstants.definition, goTerm.getDef());
        newGOInstance.setAttributeValue(ReactomeJavaConstants.referenceDatabase, getGOReferenceDatabaseOrThrow());
        if (schemaClass.getName().equals(ReactomeJavaConstants.GO_MolecularFunction)) {
            List<String> ecNumbers = goTerm.getEcNumbers();
            if (ecNumbers != null) {
                newGOInstance.setAttributeValue(ReactomeJavaConstants.ecNumber, ecNumbers);
            }
        }
        InstanceDisplayNameGenerator.setDisplayName(newGOInstance);
        GKInstance instEd = GoUpdateInstanceEditUtils.getInstanceEditForClass(
            GoUpdateInstanceEditUtils.GOUpdateInstEditType.NEW, this.getClass());
        newGOInstance.setAttributeValue(ReactomeJavaConstants.created, instEd);
        newGOInstance.setDbAdaptor(this.adaptor);
        this.adaptor.storeInstance(newGOInstance);

        return newGOInstance;
    }

    public GKInstance createNewGOInstanceIfNotObsolete(GoTerm goTerm) throws Exception {
        if (isObsolete(goTerm)) {
            return null;
        }

        return createNewGOInstance(goTerm);
    }

    private GKInstance getGOReferenceDatabaseOrThrow() throws Exception {
        return ((Set<GKInstance>) adaptor.fetchInstanceByAttribute(
            ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants.name, "=","GO")
        ).stream().findFirst().get();
    }
}
