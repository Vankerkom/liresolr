/*
 * This file is part of the LIRE project: http://www.semanticmetadata.net/lire
 * LIRE is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * LIRE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LIRE; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * We kindly ask you to refer the any or one of the following publications in
 * any publication mentioning or employing Lire:
 *
 * Lux Mathias, Savvas A. Chatzichristofis. Lire: Lucene Image Retrieval -
 * An Extensible Java CBIR Library. In proceedings of the 16th ACM International
 * Conference on Multimedia, pp. 1085-1088, Vancouver, Canada, 2008
 * URL: http://doi.acm.org/10.1145/1459359.1459577
 *
 * Lux Mathias. Content Based Image Retrieval with LIRE. In proceedings of the
 * 19th ACM International Conference on Multimedia, pp. 735-738, Scottsdale,
 * Arizona, USA, 2011
 * URL: http://dl.acm.org/citation.cfm?id=2072432
 *
 * Mathias Lux, Oge Marques. Visual Information Retrieval using Java and LIRE
 * Morgan & Claypool, 2013
 * URL: http://www.morganclaypool.com/doi/abs/10.2200/S00468ED1V01Y201301ICR025
 *
 * Copyright statement:
 * --------------------
 * (c) 2002-2013 by Mathias Lux (mathias@juggle.at)
 *     http://www.semanticmetadata.net/lire, http://www.lire-project.net
 */

package net.semanticmetadata.lire.solr.indexing;

import net.semanticmetadata.lire.imageanalysis.features.GlobalFeature;
import net.semanticmetadata.lire.indexers.hashing.BitSampling;
import net.semanticmetadata.lire.indexers.hashing.MetricSpaces;
import net.semanticmetadata.lire.indexers.parallel.WorkItem;
import net.semanticmetadata.lire.solr.FeatureRegistry;
import net.semanticmetadata.lire.solr.HashingMetricSpacesManager;
import net.semanticmetadata.lire.utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * This indexing application allows for parallel extraction of global features from multiple image files for
 * use with the LIRE Solr plugin. It basically takes a list of images, i.e. created by something like
 * "dir /s /b &gt; list.txt" or "find /path/to/images -name "*.jpg" &gt; list.txt".
 *
 * use it like:
 * <pre>$&gt; java -jar lire-request-handler.jar -i &lt;infile&gt; [-o &lt;outfile&gt;] [-n &lt;threads&gt;] [-f]</pre>
 *
 * Available options are:
 * <ul>
 * <li> -i &lt;infile&gt; ... gives a file with a list of images to be indexed, one per line.</li>
 * <li> -o &lt;outfile&gt; ... gives XML file the output is written to. if none is given the outfile is &lt;infile&gt;.xml</li>
 * <li> -n &lt;threads&gt; ... gives the number of threads used for extraction. The number of cores is a good value for that.</li>
 * <li> -f ... forces to overwrite the &lt;outfile&gt;. If the &lt;outfile&gt; already exists and -f is not given, then the operation is aborted.</li>
 * <li> -a ... use both BitSampling and MetricSpaces.</li>
 * <li> -l ... disables BitSampling and uses MetricSpaces instead.</li>
 * </ul>
 * <p>
 * TODO: Make feature list change-able
 * </p>
 * You then basically need to enrich the file with whatever metadata you prefer and send it to Solr using for instance curl:
 * <pre>curl http://localhost:9000/solr/lire/update  -H "Content-Type: text/xml" --data-binary @extracted_file.xml
 * curl http://localhost:9000/solr/lire/update  -H "Content-Type: text/xml" --data-binary "&lt;commit/&gt;"</pre>
 *
 * @author Mathias Lux, mathias@juggle.at on  13.08.2013
 */
public class ParallelSolrIndexer implements Runnable {
    private final int maxCacheSize = 250;
    //    private static HashMap<Class, String> classToPrefix = new HashMap<Class, String>(5);
    private boolean force = false;
    private static boolean individualFiles = false;
    private static int numberOfThreads = 8;

    private boolean useMetricSpaces = false, useBitSampling = true;

    LinkedBlockingQueue<WorkItem> images = new LinkedBlockingQueue<WorkItem>(maxCacheSize);
    boolean ended = false;
    int overallCount = 0;
    OutputStream dos = null;
    Set<Class> listOfFeatures;

    File fileList = null;
    File outFile = null;
    private int monitoringInterval = 1;

    public ParallelSolrIndexer() {
        // default constructor.
        listOfFeatures = new HashSet<Class>();

        HashingMetricSpacesManager.init(); // load reference points from disk.

    }

    /**
     * Sets the number of consumer threads that are employed for extraction
     *
     * @param numberOfThreads
     */
    public static void setNumberOfThreads(int numberOfThreads) {
        ParallelSolrIndexer.numberOfThreads = numberOfThreads;
    }

    public static void main(String[] args) throws IOException {
        HashingMetricSpacesManager.init();
        ParallelSolrIndexer e = new ParallelSolrIndexer();

        // parse programs args ...
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-i")) {
                // infile ...
                if ((i + 1) < args.length)
                    e.setFileList(new File(args[i + 1]));
                else {
                    System.err.println("Could not set out file.");
                    printHelp();
                }
            } else if (arg.startsWith("-o")) {
                // out file, if it's not set a single file for each input image is created.
                if ((i + 1) < args.length)
                    e.setOutFile(new File(args[i + 1]));
                else printHelp();
            } else if (arg.startsWith("-f") || arg.startsWith("--force")) {
                e.setForce(true);
            } else if (arg.startsWith("-y") || arg.startsWith("--features")) {
                if ((i + 1) < args.length) {
                    // parse and check the features.
                    String[] ft = args[i + 1].split(",");
                    for (int j = 0; j < ft.length; j++) {
                        String s = ft[j].trim();
                        if (FeatureRegistry.getClassForCode(s) != null) {
                            e.addFeature(FeatureRegistry.getClassForCode(s));
                        }
                    }
                }
            } else if (arg.startsWith("-a")) {
                e.setUseBothHashingAlgortihms(true);
            } else if (arg.startsWith("-l")) {
                e.setUseMetricSpaces(true);
            } else if (arg.startsWith("-h")) {
                // help
                printHelp();
                System.exit(0);
            } else if (arg.startsWith("-n")) {
                if ((i + 1) < args.length)
                    try {
                        ParallelSolrIndexer.numberOfThreads = Integer.parseInt(args[i + 1]);
                    } catch (Exception e1) {
                        System.err.println("Could not set number of threads to \"" + args[i + 1] + "\".");
                        e1.printStackTrace();
                    }
                else printHelp();
            }
        }
        // check if there is an infile, an outfile and some features to extract.
        if (!e.isConfigured()) {
            printHelp();
        } else {
            e.run();
        }
    }

    private static void printHelp() {
        System.out.println("This help text is shown if you start the ParallelSolrIndexer with the '-h' option.\n" +
                "\n" +
                "$> ParallelSolrIndexer -i <infile> [-o <outfile>] [-n <threads>] [-f] [-p] [-l] [-a] \\\\ \n" +
                "         [-y <list of feature classes>]\n" +
                "\n" +
                "Note: if you don't specify an outfile just \".xml\" is appended to the input image for output. So there will be one XML\n" +
                "file per image. Specifying an outfile will collect the information of all images in one single file.\n" +
                "\n" +
                "-n ... number of threads should be something your computer can cope with. default is 4.\n" +
                "-f ... forces overwrite of outfile\n" +
                "-a ... use both BitSampling and MetricSpaces.\n" +
                "-l ... disables BitSampling and uses MetricSpaces instead.\n" +
                "-y ... defines which feature classes are to be extracted. default is \"-y ph,cl,eh,jc\". \"-y ce,ac\" would \n" +
                "       add to the other four features. ");
    }

    public static String arrayToString(int[] values) {
        return Arrays.stream(values)
                .mapToObj(Integer::toHexString)
                .collect(Collectors.joining(" "));
    }

    /**
     * Adds a feature to the extractor chain. All those features are extracted from images.
     *
     * @param feature
     */
    public void addFeature(Class feature) {
        listOfFeatures.add(feature);
    }

    /**
     * Sets the file list for processing. One image file per line is fine.
     *
     * @param fileList
     */
    public void setFileList(File fileList) {
        this.fileList = fileList;
    }

    /**
     * Sets the outfile. The outfile has to be in a folder parent to all input images.
     *
     * @param outFile
     */
    public void setOutFile(File outFile) {
        this.outFile = outFile;
    }

    private boolean isConfigured() {
        boolean configured = true;
        if (fileList == null || !fileList.exists()) configured = false;
        else if (outFile == null) {
            individualFiles = true;
            // create an outfile ...
//            try {
//                outFile = new File(fileList.getCanonicalPath() + ".xml");
//                System.out.println("Setting out file to " + outFile.getCanonicalFile());
//            } catch (IOException e) {
//                configured = false;
//            }
        } else if (outFile.exists() && !force) {
            System.err.println(outFile.getName() + " already exists. Please delete or choose another outfile.");
            configured = false;
        }
        return configured;
    }

    @Override
    public void run() {
        // check:
        if (fileList == null || !fileList.exists()) {
            System.err.println("No text file with a list of images given.");
            return;
        }
        System.out.println("Extracting features: ");
        for (Iterator<Class> iterator = listOfFeatures.iterator(); iterator.hasNext(); ) {
            System.out.println("\t" + iterator.next().getCanonicalName());
        }
        try {
            if (!individualFiles) {
                // create a BufferedOutputStream with a large buffer
                dos = new BufferedOutputStream(new FileOutputStream(outFile), 1024 * 1024 * 8);
                dos.write("<add>\n".getBytes());
            }
            Thread p = new Thread(new Producer(), "Producer");
            p.start();
            LinkedList<Thread> threads = new LinkedList<Thread>();
            long l = System.currentTimeMillis();
            for (int i = 0; i < numberOfThreads; i++) {
                Thread c = new Thread(new Consumer(), "Consumer-" + i);
                c.start();
                threads.add(c);
            }
            if (ParallelSolrIndexer.numberOfThreads > 1) {
                Thread m = new Thread(new Monitoring(), "Monitoring");
                m.start();
            }
            for (Iterator<Thread> iterator = threads.iterator(); iterator.hasNext(); ) {
                iterator.next().join();
            }
            long l1 = System.currentTimeMillis() - l;
            System.out.println("Analyzed " + overallCount + " images in " + l1 / 1000 + " seconds, ~" + (overallCount > 0 ? (l1 / overallCount) : "inf.") + " ms each.");
            if (!individualFiles) {
                dos.write("</add>\n".getBytes());
                dos.close();
            }
//            writer.commit();
//            writer.close();
//            threadFinished = true;

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void addFeatures(List features) {
        for (Iterator<Class> iterator = listOfFeatures.iterator(); iterator.hasNext(); ) {
            Class next = iterator.next();
            try {
                features.add(next.newInstance());
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    public void setUseMetricSpaces(boolean useMetricSpaces) {
        this.useMetricSpaces = useMetricSpaces;
        this.useBitSampling = !useMetricSpaces;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public void setUseBothHashingAlgortihms(boolean useBothHashingAlgortihms) {
        this.useMetricSpaces = useBothHashingAlgortihms;
        this.useBitSampling = useBothHashingAlgortihms;
    }

    class Monitoring implements Runnable {
        public void run() {
            long ms = System.currentTimeMillis();
            try {
                Thread.sleep(1000 * monitoringInterval); // wait xx seconds
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            while (!ended) {
                try {
                    // print the current status:
                    long time = System.currentTimeMillis() - ms;
                    System.out.println("Analyzed " + overallCount + " images in " + time / 1000 + " seconds, " + ((overallCount > 0) ? (time / overallCount) : "n.a.") + " ms each (" + images.size() + " images currently in queue).");
                    Thread.sleep(1000 * monitoringInterval); // wait xx seconds
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class Producer implements Runnable {
        public void run() {
            try {
                BufferedReader br = new BufferedReader(new FileReader(fileList));
                String file = null;
                File next = null;
                while ((file = br.readLine()) != null) {
                    next = new File(file);
                    try {
                        // reading from hard drive to buffer to reduce the load on the HDD and move decoding to the
                        // consumers using java.nio
                        int fileSize = (int) next.length();
                        byte[] buffer = new byte[fileSize];
                        FileInputStream fis = new FileInputStream(next);
                        FileChannel channel = fis.getChannel();
                        MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
                        map.load();
                        map.get(buffer);
                        String path = next.getCanonicalPath();
                        images.put(new WorkItem(path, buffer));
                    } catch (Exception e) {
                        System.err.println("Could not read image " + file + ": " + e.getMessage());
                    }
                }
                for (int i = 0; i < numberOfThreads*2; i++) {
                    String tmpString = null;
                    byte[] tmpImg = null;
                    try {
                        images.put(new WorkItem(tmpString, tmpImg));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
            ended = true;
        }
    }

    class Consumer implements Runnable {
        WorkItem tmp = null;
        LinkedList<GlobalFeature> features = new LinkedList<GlobalFeature>();
        int count = 0;
        boolean locallyEnded = false;
        StringBuilder sb = new StringBuilder(1024);

        Consumer() {
            addFeatures(features);
        }

        public void run() {
            while (!locallyEnded) {
                try {
                    // we wait for the stack to be either filled or empty & not being filled any more.
                    // make sure the thread locally knows that the end has come (outer loop)
//                    if (images.peek().getBuffer() == null)
//                        locallyEnded = true;
                    // well the last thing we want is an exception in the very last round.
                    if (!locallyEnded) {
                        tmp = images.take();
                        if (tmp.getBuffer() == null)
                            locallyEnded = true;
                        else {
                            count++;
                            overallCount++;
                        }
                    }

                    if (!locallyEnded) {
                        sb.delete(0, sb.length());
                        ByteArrayInputStream b = new ByteArrayInputStream(tmp.getBuffer());

                        // reads the image. Make sure twelve monkeys lib is in the path to read all jpegs and tiffs.
                        BufferedImage read = ImageIO.read(b);
                        // converts color space to INT_RGB
                        BufferedImage img = ImageUtils.createWorkingCopy(read);                        

                        // --------< creating doc >-------------------------
                        sb.append("<doc>");
                        sb.append("<field name=\"id\">");
                        sb.append(tmp.getFileName());
                        sb.append("</field>");

                        for (GlobalFeature feature : features) {
                            String featureCode = FeatureRegistry.getCodeForClass(feature.getClass());
                            if (featureCode != null) {
                                feature.extract(img);
                                String histogramField = FeatureRegistry.codeToFeatureField(featureCode);
                                String hashesField = FeatureRegistry.codeToHashField(featureCode);
                                String metricSpacesField = FeatureRegistry.codeToMetricSpacesField(featureCode);

                                sb.append("<field name=\"" + histogramField + "\">");
                                sb.append(Base64.getEncoder().encodeToString(feature.getByteArrayRepresentation()));
                                sb.append("</field>");
                                if (useBitSampling) {
                                    sb.append("<field name=\"" + hashesField + "\">");
                                    sb.append(arrayToString(BitSampling.generateHashes(feature.getFeatureVector())));
                                    sb.append("</field>");
                                }
                                if (useMetricSpaces && MetricSpaces.supportsFeature(feature)) {
                                    sb.append("<field name=\"" + metricSpacesField + "\">");
                                    sb.append(MetricSpaces.generateHashString(feature));
                                    sb.append("</field>");
                                }
                            }
                        }
                        sb.append("</doc>\n");

                        // --------< / creating doc >-------------------------

                        // finally write everything to the stream - in case no exception was thrown..
                        if (!individualFiles) {
                            synchronized (dos) {
                                dos.write(sb.toString().getBytes());
                                // dos.flush();  // flushing takes too long ... better not.
                            }
                        } else {
                            OutputStream mos = new BufferedOutputStream(new FileOutputStream(tmp.getFileName() + "_solr.xml"));
                            mos.write(sb.toString().getBytes());
                            mos.flush();
                            mos.close();
                        }
                    }
//                    if (!individualFiles) {
//                        synchronized (dos) {
//                            dos.write(buffer.toString().getBytes());
//                        }
//                    }
                } catch (Exception e) {
                    System.err.println("Error processing file " + tmp.getFileName());
                    e.printStackTrace();
                }
            }
        }
    }


}
