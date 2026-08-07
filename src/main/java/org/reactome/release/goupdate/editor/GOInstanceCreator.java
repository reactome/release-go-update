package org.reactome.release.goupdate.editor;

import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.utils.CuratorToolAPI;

import java.util.Collections;
import java.util.List;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;
import static org.reactome.release.goupdate.utils.Utils.toShell;

public class GOInstanceCreator {
    private final CuratorToolAPI curatorToolAPI;

    private static SimpleInstance goReferenceDatabase;

    public GOInstanceCreator(CuratorToolAPI curatorToolAPI) {
        this.curatorToolAPI = curatorToolAPI;
    }

    public SimpleInstance createNewGOInstance(GoTerm goTerm) {
        SimpleInstance newGOInstance = new SimpleInstance();

        String schemaClassName = goTerm.getNamespace().getReactomeName();
        newGOInstance.setSchemaClassName(schemaClassName);
        newGOInstance.setDefaultPersonId(getPersonId());
        newGOInstance.setAttribute(ReactomeJavaConstants.identifier, goTerm.getId());
        // "name" is multi-valued in the data model, and curator-tool-ws matches the model's set method by the
        // value's own type, so a bare String would be dropped instead of stored.
        newGOInstance.setAttribute(ReactomeJavaConstants.name, Collections.singletonList(goTerm.getName()));
        newGOInstance.setAttribute(ReactomeJavaConstants.definition, goTerm.getDef());
        newGOInstance.setAttribute(ReactomeJavaConstants.referenceDatabase, toShell(getGOReferenceDatabase()));
        if (schemaClassName.equals(ReactomeJavaConstants.GO_MolecularFunction)) {
            String ecNumber = goTerm.getEcNumber();
            if (ecNumber != null) {
                newGOInstance.setAttribute(ReactomeJavaConstants.ecNumber, ecNumber);
            }
        }
        newGOInstance.setDisplayName(goTerm.getName());

        getCuratorToolAPI().commit(newGOInstance);

        return newGOInstance;
    }

    public SimpleInstance createNewGOInstanceIfNotObsolete(GoTerm goTerm)  {
        if (isObsolete(goTerm)) {
            return null;
        }

        return createNewGOInstance(goTerm);
    }

    private SimpleInstance getGOReferenceDatabase() {
        if (goReferenceDatabase == null) {
            goReferenceDatabase = getCuratorToolAPI().fetchGOReferenceDatabase();
        }
        return goReferenceDatabase;
    }

    private long getPersonId() {
        return getCuratorToolAPI().getPersonId();
    }

    private CuratorToolAPI getCuratorToolAPI() {
        return this.curatorToolAPI;
    }
}
