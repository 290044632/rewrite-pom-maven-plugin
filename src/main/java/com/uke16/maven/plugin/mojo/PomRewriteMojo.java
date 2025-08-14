package com.uke16.maven.plugin.mojo;

import org.apache.commons.compress.archivers.jar.JarArchiveEntry;
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream;
import org.apache.commons.compress.archivers.jar.JarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.jar.JarUnArchiver;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@Mojo(name = "rewrite")
public class PomRewriteMojo extends AbstractMojo {

    private static final String POM_XML = "pom.xml";
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject mavenProject;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Log log = getLog();
        try {
            if(skip){
                log.info("Skip pom.xml rewrite ");
                return;
            }
            Path backupJar = backupJar(mavenProject);
            Path rewrittenPomXml = rewritePomXml(mavenProject);

            repackage(backupJar, getJarPath(mavenProject), rewrittenPomXml);

        } catch (Exception e) {
            log.error(e);
            throw new MojoExecutionException(e);
        }
    }

    private void repackage(Path sourceJar, String targetJarPath, Path xmlFilePath) throws IOException {
        try (JarArchiveOutputStream jarArchiveOutputStream = new JarArchiveOutputStream(Files.newOutputStream(Paths.get(targetJarPath)));
             JarFile jarFile = new JarFile(sourceJar.toFile());) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                if (jarEntry.getName().endsWith(POM_XML)) {
                    ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(xmlFilePath, jarEntry.getName());
                    zipArchiveEntry.setMethod(jarEntry.getMethod());
                    zipArchiveEntry.setComment(jarEntry.getComment());
                    JarArchiveEntry jarArchiveEntry = new JarArchiveEntry(zipArchiveEntry);
                    putJarArchiveEntry(jarArchiveOutputStream, jarArchiveEntry, Files.newInputStream(xmlFilePath));
                } else {
                    InputStream stream = jarFile.getInputStream(jarEntry);
                    JarArchiveEntry jarArchiveEntry = new JarArchiveEntry(jarEntry);
                    putJarArchiveEntry(jarArchiveOutputStream, jarArchiveEntry, stream);
                }
            }
        } finally {
            Files.deleteIfExists(xmlFilePath);
            getLog().info("Repackage jar : " + sourceJar + " to " + targetJarPath);
        }
    }

    private void putJarArchiveEntry(JarArchiveOutputStream jarArchiveOutputStream, JarArchiveEntry jarArchiveEntry, InputStream inputStream) throws IOException {
        jarArchiveOutputStream.putArchiveEntry(jarArchiveEntry);
        byte[] b = new byte[1024];
        int length = 0;
        while ((length = inputStream.read(b)) != -1) {
            jarArchiveOutputStream.write(b, 0, length);
        }
        jarArchiveOutputStream.closeArchiveEntry();
        inputStream.close();
    }

    private Path backupJar(MavenProject mavenProject) throws IOException {
        String sourceJar = getJarPath(mavenProject);
        Path target = Paths.get(sourceJar + ".original");
        Files.deleteIfExists(target);
        Path path = Files.copy(Paths.get(sourceJar), target);
        getLog().info("Backing jar : " + sourceJar + " to " + path);
        return path;
    }

    private String getJarPath(MavenProject mavenProject) {
        return mavenProject.getBuild().getDirectory() + File.separator + mavenProject.getBuild().getFinalName() + "." + mavenProject.getPackaging();
    }

    private Path rewritePomXml(MavenProject mavenProject) throws Exception {
        List<Profile> activeProfiles = mavenProject.getActiveProfiles();
        getLog().info("Active Profiles...." + activeProfiles.stream().map(Profile::getId).distinct().collect(Collectors.joining(",")));
        String targetDir = mavenProject.getBuild().getDirectory();
        File newPomXmlFile = Paths.get(targetDir, POM_XML).toFile();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = factory.newDocumentBuilder();
        Document document = documentBuilder.parse(new File(mavenProject.getBasedir(), POM_XML));
        NodeList nodeList = document.getElementsByTagName("profile");
        List<String> profiles = activeProfiles.stream().map(Profile::getId).collect(Collectors.toList());
        int count = nodeList.getLength();
        List<Node> removeNodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = getChildNodeValue(nodeList.item(i), "id");
            if (id != null && profiles.contains(id)) {
                continue;
            }
            removeNodes.add(nodeList.item(i));
        }
        if (!removeNodes.isEmpty()) {
            NodeList profilesNodeList = document.getElementsByTagName("profiles");
            Node parent = profilesNodeList.item(0);
            for (Node node : removeNodes) {
                parent.removeChild(node);
            }
        }
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(newPomXmlFile);
        transformer.transform(source, result);
        return newPomXmlFile.toPath();
    }

    private String getChildNodeValue(Node node, String childNodeName) {
        NodeList childNodes = node.getChildNodes();
        for (int j = 0; j < childNodes.getLength(); j++) {
            Node item = childNodes.item(j);
            if (item.getNodeName().equals(childNodeName)) {
                return item.getTextContent();
            }
        }
        return null;
    }
}
