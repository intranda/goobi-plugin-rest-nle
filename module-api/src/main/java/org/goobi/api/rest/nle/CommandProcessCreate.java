package org.goobi.api.rest.nle;

import java.util.Date;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.managedbeans.LoginBean;
import org.goobi.production.enums.PluginType;
import org.goobi.production.flow.jobs.HistoryAnalyserJob;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IOpacPlugin;

import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.UghHelper;
import de.sub.goobi.helper.enums.StepEditType;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.StepManager;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@Path("/process")

public class CommandProcessCreate {

    @Context
    UriInfo uriInfo;

    @Path("/nlecreate")
    @POST
    @Consumes({ MediaType.TEXT_XML, MediaType.APPLICATION_XML })
    @Produces(MediaType.TEXT_XML)
    public Response createProcessForNle(NleCreationRequest req, @Context final HttpServletResponse response) {

        NleCreationResponse cr = new NleCreationResponse();
        String processtitle = UghHelper.convertUmlaut(req.getIdentifier().replace(":", "_")).toLowerCase();
        processtitle.replaceAll("[\\W]", "");

        Process p = ProcessManager.getProcessByTitle(processtitle);
        if (p != null) {
            cr.setResult("error");
            cr.setErrorText("Process " + req.getIdentifier() + " already exists.");
            cr.setProcessId(p.getId());
            cr.setProcessName(p.getTitel());
            //            response.setStatus(HttpServletResponse.SC_CONFLICT);
            Response resp = Response.status(Response.Status.CONFLICT).entity(cr).build();
            return resp;
        }

        Process template = ProcessManager.getProcessByTitle(req.getGoobiWorkflow());
        if (template == null) {
            cr.setResult("error");
            cr.setErrorText("Process template " + req.getGoobiWorkflow() + " does not exist.");
            cr.setProcessId(0);
            cr.setProcessName(req.getIdentifier());
            Response resp = Response.status(Response.Status.BAD_REQUEST).entity(cr).build();
            return resp;
        }

        Prefs prefs = template.getRegelsatz().getPreferences();
        Fileformat fileformat = null;
        try {
            fileformat = new MetsMods(prefs);
            DigitalDocument digDoc = new DigitalDocument();
            fileformat.setDigitalDocument(digDoc);
            DocStruct logical = digDoc.createDocStruct(prefs.getDocStrctTypeByName(req.getType()));
            DocStruct physical = digDoc.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            digDoc.setLogicalDocStruct(logical);
            digDoc.setPhysicalDocStruct(physical);

            // metadata
            if (StringUtils.isNotBlank(req.getTitle())) {
                Metadata title = new Metadata(prefs.getMetadataTypeByName("TitleDocMain"));
                title.setValue(req.getTitle());
                logical.addMetadata(title);
            }
            Metadata identifierDigital = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
            identifierDigital.setValue(req.getIdentifier());
            logical.addMetadata(identifierDigital);
            if (StringUtils.isNotBlank(req.getIdentifier())) {
                Metadata identifierSource = new Metadata(prefs.getMetadataTypeByName("CatalogIDSource"));
                identifierSource.setValue(req.getIdentifier());
                logical.addMetadata(identifierSource);
            }
        } catch (UGHException e) {
            cr.setResult("error");
            cr.setErrorText("Error during metadata creation for " + req.getIdentifier() + ": " + e.getMessage());
            Response resp = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(cr).build();
            return resp;
        }
        Process process = cloneTemplate(template);
        // set title
        process.setTitel(processtitle);

        if (StringUtils.isNotBlank(req.getProject())) {
            List<Project> projects = ProjectManager.getAllProjects();
            for (Project proj : projects) {
                if (proj.getTitel().equals(req.getProject())) {
                    process.setProjekt(proj);
                }
            }
        }

        try {
            NeuenProzessAnlegen(process, template, fileformat, prefs);
        } catch (Exception e) {
            cr.setResult("error");
            cr.setErrorText("Error during process creation for " + req.getIdentifier() + ": " + e.getMessage());
            Response resp = Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(cr).build();
            return resp;
        }

        if (StringUtils.isNotBlank(req.getIdentifier())) {
            Processproperty pp = new Processproperty();
            pp.setTitel("Identifier");
            pp.setWert(req.getIdentifier());
            pp.setProcessId(process.getId());
            PropertyManager.saveProcessProperty(pp);
        }
        if (StringUtils.isNotBlank(req.getTitle())) {
            Processproperty pp = new Processproperty();
            pp.setTitel("Title");
            pp.setWert(req.getTitle());
            pp.setProcessId(process.getId());
            PropertyManager.saveProcessProperty(pp);
        }
        if (StringUtils.isNotBlank(req.getType())) {
            Processproperty pp = new Processproperty();
            pp.setTitel("Type");
            pp.setWert(req.getType());
            pp.setProcessId(process.getId());
            PropertyManager.saveProcessProperty(pp);
        }
        if (StringUtils.isNotBlank(req.getProject())) {
            Processproperty pp = new Processproperty();
            pp.setTitel("Project");
            pp.setWert(req.getProject());
            pp.setProcessId(process.getId());
            PropertyManager.saveProcessProperty(pp);
        }
        if (StringUtils.isNotBlank(req.getGoobiWorkflow())) {
            Processproperty pp = new Processproperty();
            pp.setTitel("GoobiWorkflow");
            pp.setWert(req.getGoobiWorkflow());
            pp.setProcessId(process.getId());
            PropertyManager.saveProcessProperty(pp);
        }
        cr.setResult("success");
        cr.setProcessName(process.getTitel());
        cr.setProcessId(process.getId());
        Response resp = Response.status(Response.Status.CREATED).entity(cr).build();
        return resp;
    }

    private Process cloneTemplate(Process template) {
        Process process = new Process();

        process.setIstTemplate(false);
        process.setInAuswahllisteAnzeigen(false);
        process.setProjekt(template.getProjekt());
        process.setRegelsatz(template.getRegelsatz());
        process.setDocket(template.getDocket());

        BeanHelper bHelper = new BeanHelper();
        bHelper.SchritteKopieren(template, process);
        bHelper.ScanvorlagenKopieren(template, process);
        bHelper.WerkstueckeKopieren(template, process);
        bHelper.EigenschaftenKopieren(template, process);

        return process;
    }

    private Fileformat getOpacRequest(String opacIdentifier, Prefs prefs, String myCatalogue) throws Exception {
        // get logical data from opac
        ConfigOpacCatalogue coc = ConfigOpac.getInstance().getCatalogueByName(myCatalogue);
        //        ConfigOpacCatalogue coc = new ConfigOpac().getCatalogueByName(myCatalogue);
        IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
        Fileformat ff = myImportOpac.search("12", opacIdentifier, coc, prefs);
        Metadata md = new Metadata(prefs.getMetadataTypeByName("singleDigCollection"));
        md.setValue("DefaultCollection");
        DocStruct log = ff.getDigitalDocument().getLogicalDocStruct();
        log.addMetadata(md);
        MetadataType sourceType = prefs.getMetadataTypeByName("CatalogIDSource");
        MetadataType digType = prefs.getMetadataTypeByName("CatalogIDDigital");
        if (log.getAllMetadataByType(sourceType).isEmpty()) {
            Metadata source = new Metadata(sourceType);
            source.setValue(opacIdentifier);
            log.addMetadata(source);
        }

        if (log.getAllMetadataByType(digType).isEmpty()) {
            Metadata source = new Metadata(digType);
            source.setValue(opacIdentifier);
            log.addMetadata(source);
        }

        return ff;
    }

    public String NeuenProzessAnlegen(Process process, Process template, Fileformat ff, Prefs prefs) throws Exception {

        for (Step step : process.getSchritteList()) {

            step.setBearbeitungszeitpunkt(process.getErstellungsdatum());
            step.setEditTypeEnum(StepEditType.AUTOMATIC);
            LoginBean loginForm = Helper.getLoginBean();
            if (loginForm != null) {
                step.setBearbeitungsbenutzer(loginForm.getMyBenutzer());
            }

            if (step.getBearbeitungsstatusEnum() == StepStatus.DONE) {
                step.setBearbeitungsbeginn(process.getErstellungsdatum());

                Date myDate = new Date();
                step.setBearbeitungszeitpunkt(myDate);
                step.setBearbeitungsende(myDate);
            }

        }

        ProcessManager.saveProcess(process);

        /*
         * -------------------------------- Imagepfad hinzufügen (evtl. vorhandene zunächst löschen) --------------------------------
         */
        try {
            MetadataType mdt = prefs.getMetadataTypeByName("pathimagefiles");
            List<? extends Metadata> alleImagepfade = ff.getDigitalDocument().getPhysicalDocStruct().getAllMetadataByType(mdt);
            if (alleImagepfade != null && alleImagepfade.size() > 0) {
                for (Metadata md : alleImagepfade) {
                    ff.getDigitalDocument().getPhysicalDocStruct().getAllMetadata().remove(md);
                }
            }
            Metadata newmd = new Metadata(mdt);
            if (SystemUtils.IS_OS_WINDOWS) {
                newmd.setValue("file:/" + process.getImagesDirectory() + process.getTitel().trim() + "_tif");
            } else {
                newmd.setValue("file://" + process.getImagesDirectory() + process.getTitel().trim() + "_tif");
            }
            ff.getDigitalDocument().getPhysicalDocStruct().addMetadata(newmd);

            /* Rdf-File schreiben */
            process.writeMetadataFile(ff);

        } catch (ugh.exceptions.DocStructHasNoTypeException | MetadataTypeNotAllowedException e) {
            return e.getMessage();
        }

        // Adding process to history
        HistoryAnalyserJob.updateHistoryForProzess(process);

        ProcessManager.saveProcess(process);

        process.readMetadataFile();

        List<Step> steps = StepManager.getStepsForProcess(process.getId());
        for (Step s : steps) {
            if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                myThread.start();
            }
        }
        return "";
    }

}
