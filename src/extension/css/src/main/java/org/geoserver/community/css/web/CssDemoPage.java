/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.community.css.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.wicket.PageParameters;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalWindow;
import org.apache.wicket.extensions.ajax.markup.html.tabs.AjaxTabbedPanel;
import org.apache.wicket.extensions.markup.html.tabs.AbstractTab;
import org.apache.wicket.extensions.markup.html.tabs.ITab;
import org.apache.wicket.extensions.markup.html.tabs.PanelCachingTab;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.FeedbackPanel;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.geoscript.geocss.CssParser;
import org.geoscript.geocss.Translator;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.config.GeoServerDataDirectory;
import org.geoserver.web.GeoServerSecuredPage;
import org.geoserver.web.wicket.ParamResourceModel;
import org.geotools.styling.SLDTransformer;
import org.geotools.styling.Style;

public class CssDemoPage extends GeoServerSecuredPage {
    
    /**
     * Eventually prefixes the 
     * @author Andrea Aime - GeoSolutions
     *
     */
    public class StyleNameModel extends LoadableDetachableModel<String> {

        private StyleInfo style;

        public StyleNameModel(StyleInfo style) {
            this.style = style;
        }

        @Override
        protected String load() {
            if(style.getWorkspace() != null) {
                return style.getWorkspace().getName() + ":" + style.getName();
            } else {
                return style.getName();
            }
        }

    }

    /*
     * @TODO: externalize this string to a classpath resource
     */
    public static String defaultStyle = "* { fill: lightgrey; }";

    public OpenLayersMapPanel map = null;
    public Label sldPreview = null;
    private LayerInfo layer = null;
    private StyleInfo style = null;

    public CssDemoPage() {
        this(new PageParameters());
    }

    public CssDemoPage(PageParameters params) {
        this.layer = extractLayer(params, getCatalog());
        this.style = extractStyle(params, getCatalog(), this.layer);
        if (this.layer == null || this.style == null) {
            doErrorLayout();
        } else {
            doMainLayout();
        }
    }

    private static LayerInfo extractLayer(PageParameters params, Catalog catalog) {
        if (params.containsKey("layer")) {
            String name = params.getString("layer");
            return catalog.getLayerByName(name);
        } else {
            // TODO: Revisit this behavior
            // give some slight preference to the topp:states layer to make
            // demoing a bit more consistent.
            LayerInfo states = catalog.getLayerByName("topp:states");
            if (states != null) {
                return states;
            } else {
                List<LayerInfo> layers = catalog.getLayers();
                if (layers.size() > 0) {
                    return layers.get(0);
                } else {
                    return null;
                }
            }
        }
    }

    private static StyleInfo extractStyle(PageParameters params, Catalog catalog, LayerInfo layer) {
        if (params.containsKey("style")) {
            String style = params.getString("style");
            String[] parts = style.split(":", 2);
            if (parts.length == 1) {
                return catalog.getStyleByName(parts[0]);
            } else if (parts.length == 2) {
                return catalog.getStyleByName(parts[0], parts[1]);
            } else {
                throw new IllegalStateException("After splitting, there should be only 1 or 2 parts.  Got: " + Arrays.toString(parts));
            }
        } else {
            if (layer != null) {
                return layer.getDefaultStyle();
            } else {
                return null;
            }
        }
    }
 
    public File findStyleFile(StyleInfo style) {
        try {
            GeoServerDataDirectory datadir = 
                new GeoServerDataDirectory(getCatalog().getResourceLoader());
            return datadir.findStyleSldFile(style);
        } catch (IOException ioe) {
            throw new WicketRuntimeException(ioe);
        }
    }

    public Catalog catalog() { return getCatalog(); }

    public StyleInfo getStyleInfo() { return this.style; } 

    public LayerInfo getLayer() { return this.layer; } 
    
    public String cssText2sldText(String css, StyleInfo styleInfo) {
        try {
            GeoServerDataDirectory datadir = 
                new GeoServerDataDirectory(getCatalog().getResourceLoader());
            File styleDir;
            if(styleInfo == null || styleInfo.getWorkspace() == null) {
                styleDir= datadir.findStyleDir();
            } else {
                File ws = datadir.findOrCreateWorkspaceDir(styleInfo.getWorkspace());
                styleDir = new File(ws, "styles");
                if(!styleDir.exists()) {
                    styleDir.mkdir();
                }
            }

            scala.collection.Seq<org.geoscript.geocss.Rule> rules = CssParser.parse(css).get();
            Translator translator = 
                new Translator(scala.Option.apply(styleDir.toURI().toURL()));
            Style style = translator.css2sld(rules);

            SLDTransformer tx = new org.geotools.styling.SLDTransformer();
            tx.setIndentation(2);
            StringWriter sldChars = new java.io.StringWriter();
            tx.transform(style, sldChars);
            return sldChars.toString();
        } catch (Exception e) {
            throw new WicketRuntimeException("Error while parsing stylesheet [" + css + "] : " + e);
        }
    }

    void createCssTemplate(String workspace, String name) {
        try {
            Catalog catalog = getCatalog();
            if (catalog.getStyleByName(workspace, name) == null) {
                StyleInfo style = catalog.getFactory().createStyle();
                style.setName(name);
                style.setFilename(name + ".sld");
                if(workspace != null) {
                    WorkspaceInfo ws = catalog.getWorkspaceByName(workspace);
                    if(ws == null) {
                        throw new WicketRuntimeException("Workspace does not exist: " + workspace);
                    }
                    style.setWorkspace(ws);
                }
                
                catalog.add(style);

                File sld = findStyleFile(style);
                if (sld == null || !sld.exists()) {
                    catalog.getResourcePool().writeStyle(
                            style,
                            new ByteArrayInputStream(cssText2sldText(defaultStyle, style).getBytes())
                            );
                    sld = findStyleFile(style);
                }

                File css = new File(sld.getParent(), name + ".css");
                if (!css.exists()) {
                    FileWriter writer = new FileWriter(css);
                    writer.write(defaultStyle);
                    writer.close();
                }
            }
        } catch (IOException ioe) {
            throw new WicketRuntimeException(ioe);
        }
    }

    private void doErrorLayout() {
        add(new Fragment("main-content", "loading-failure", this));
    }

    private void doMainLayout() {
        Fragment mainContent = new Fragment("main-content", "normal", this);
        final ModalWindow popup = new ModalWindow("popup");
        mainContent.add(popup);
        mainContent.add(new Label("style.name", new StyleNameModel(style)));
        mainContent.add(new Label("layer.name", new PropertyModel(layer, "prefixedName")));
        mainContent.add(new AjaxLink("change.style", new ParamResourceModel("CssDemoPage.changeStyle", this)) {
            public void onClick(AjaxRequestTarget target) {
                target.appendJavascript("Wicket.Window.unloadConfirmation = false;");
                popup.setInitialHeight(400);
                popup.setInitialWidth(600);
                popup.setTitle(new Model("Choose style to edit"));
                popup.setContent(new StyleChooser(popup.getContentId(), CssDemoPage.this));
                popup.show(target);
            }
        });
        mainContent.add(new AjaxLink("change.layer", new ParamResourceModel("CssDemoPage.changeLayer", this)) {
            public void onClick(AjaxRequestTarget target) {
                target.appendJavascript("Wicket.Window.unloadConfirmation = false;");
                popup.setInitialHeight(400);
                popup.setInitialWidth(600);
                popup.setTitle(new Model("Choose layer to edit"));
                popup.setContent(new LayerChooser(popup.getContentId(), CssDemoPage.this));
                popup.show(target);
            }
        });
        mainContent.add(new AjaxLink("create.style", new ParamResourceModel("CssDemoPage.createStyle", this)) {
            public void onClick(AjaxRequestTarget target) {
                target.appendJavascript("Wicket.Window.unloadConfirmation = false;");
                popup.setInitialHeight(200);
                popup.setInitialWidth(300);
                popup.setTitle(new Model("Choose name for new style"));
                popup.setContent(new StyleNameInput(popup.getContentId(), CssDemoPage.this));
                popup.show(target);
            }
        });
        mainContent.add(new AjaxLink("associate.styles", new ParamResourceModel("CssDemoPage.associateStyles", this)) {
            public void onClick(AjaxRequestTarget target) {
                target.appendJavascript("Wicket.Window.unloadConfirmation = false;");
                popup.setInitialHeight(400);
                popup.setInitialWidth(600);
                popup.setTitle(new Model("Choose layers to associate"));
                popup.setContent(new MultipleLayerChooser(popup.getContentId(), CssDemoPage.this));
                popup.show(target);
            }
        });

        final IModel<String> sldModel = new AbstractReadOnlyModel<String>() {
            public String getObject() {
                File file = findStyleFile(style);
                if (file != null && file.isFile()) {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new FileReader(file));
                        StringBuilder builder = new StringBuilder();
                        char[] line = new char[4096];
                        int len = 0;
                        while ((len = reader.read(line, 0, 4096)) >= 0)
                            builder.append(line, 0, len);
                        return builder.toString();
                    } catch (IOException e) {
                        throw new WicketRuntimeException(e);
                    } finally {
                        try {
                            if (reader != null) reader.close();
                        } catch (IOException e) {
                            throw new WicketRuntimeException(e);
                        }
                    }
                } else {
                    return "No SLD file found for this style. One will be generated automatically if you save the CSS.";
                }
            }
        };

        final CompoundPropertyModel model = new CompoundPropertyModel<CssDemoPage>(CssDemoPage.this);
        List<ITab> tabs = new ArrayList<ITab>();
        tabs.add(new PanelCachingTab(new AbstractTab(new Model("Generated SLD")) {
            public Panel getPanel(String id) { 
                SLDPreviewPanel panel = new SLDPreviewPanel(id, sldModel);
                sldPreview = panel.getLabel();
                return panel;
            }
        }));
        tabs.add(new PanelCachingTab(new AbstractTab(new Model("Map")) {
            public Panel getPanel(String id) { return map = new OpenLayersMapPanel(id, layer, style); }
        }));
        if(layer.getResource() instanceof FeatureTypeInfo) {
            tabs.add(new PanelCachingTab(new AbstractTab(new Model("Data")) {
                public Panel getPanel(String id) { 
                    try {
                        return new DataPanel(id, model, (FeatureTypeInfo) layer.getResource());
                    } catch (IOException e) {
                        throw new WicketRuntimeException(e);
                    }
                };
            }));
        } else if(layer.getResource() instanceof CoverageInfo) {
            tabs.add(new PanelCachingTab(new AbstractTab(new Model("Data")) {
                public Panel getPanel(String id) { 
                    return new BandsPanel(id, (CoverageInfo) layer.getResource());
                };
            }));
        }
        tabs.add(new AbstractTab(new Model("CSS Reference")) {
            public Panel getPanel(String id) { 
                return new DocsPanel(id);
            }
        });

        FeedbackPanel feedback2 = new FeedbackPanel("feedback-low");
        feedback2.setOutputMarkupId(true);
        mainContent.add(feedback2);

        File sldFile = findStyleFile(style);
        File cssFile = new File(sldFile.getParentFile(), style.getName() + ".css");

        mainContent.add(new StylePanel(
            "style.editing", model, CssDemoPage.this, getFeedbackPanel(), cssFile
        ));

        mainContent.add(new AjaxTabbedPanel("context", tabs));

        add(mainContent);
    }
}
