/*
 * Copyright (C) 2015 Tim Vaughan <tgvaughan@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package bacter.util;

import bacter.Conversion;
import bacter.ConversionGraph;
import bacter.Locus;
import beast.evolution.tree.Node;
import beast.math.statistic.DiscreteStatistics;

import javax.swing.*;
import javax.swing.border.EtchedBorder;
import java.awt.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.List;

/**
 * A rewrite of TreeAnnotator targeted at summarizing ACG logs
 * generated by bacter.
 *
 * @author Tim Vaughan <tgvaughan@gmail.com>
 */
public class ACGAnnotator {

    public enum HeightStrategy { MEAN, MEDIAN }

    static class ACGAnnotatorOptions {
        public File inFile;
        public File outFile = new File("summary.tree");
        public double burninPercentage = 10.0;
        public double convPosteriorThresholdPercentage = 50.0;
        public HeightStrategy heightStrategy = HeightStrategy.MEAN;

        @Override
        public String toString() {

        }
    }

    public ACGAnnotator(ACGAnnotatorOptions options) throws IOException {

        // Initialise reader

        ACGLogFileReader logReader = new ACGLogFileReader(options.inFile,
                options.burninPercentage);

        System.out.println(logReader.getACGCount() + " ACGs in file.");

        System.out.println("The first " + logReader.getBurnin() +
                 " (" + options.burninPercentage + "%) ACGs will be discarded " +
                "to account for burnin.");

        // Compute CF Clade probabilities

        System.out.println("\nComputing CF clade credibilities...");

        ACGCladeSystem cladeSystem = new ACGCladeSystem();

        for (ConversionGraph acg : logReader)
            cladeSystem.add(acg, true);

        cladeSystem.calculateCladeCredibilities(logReader.getCorrectedACGCount());

        // Identify MCC CF topology

        System.out.println("\nIdentifying MCC CF topology...");

        ConversionGraph acgBest = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ConversionGraph acg : logReader ) {
            double score = cladeSystem.getLogCladeCredibility(acg.getRoot(), null);

            if (score>bestScore) {
                acgBest = acg.copy();
                bestScore = score;
            }
        }

        if (acgBest == null)
            throw new IllegalStateException("Failed to find best tree topology.");

        // Remove conversions

        for (Locus locus : acgBest.getLoci())
                acgBest.getConversions(locus).clear();

        // Collect CF node heights

        System.out.println("\nCollecting CF node heights...");

        Set<String> attributeNames = new HashSet<>();
        attributeNames.add("height");

        cladeSystem = new ACGCladeSystem(acgBest);
        for (ConversionGraph acg : logReader) {
            cladeSystem.collectAttributes(acg, attributeNames);
            cladeSystem.collectConversions(acg);
        }
        cladeSystem.removeClades(acgBest.getRoot(), true);
        cladeSystem.calculateCladeCredibilities(logReader.getCorrectedACGCount());

        // Annotate node heights of winning CF topology

        annotateCF(cladeSystem, acgBest.getRoot(), options.heightStrategy);

        // Add conversion summaries

        summarizeConversions(cladeSystem, acgBest, logReader.getCorrectedACGCount(),
                options.convPosteriorThresholdPercentage/100.0,
                options.heightStrategy);


        // Write output

        System.out.println("\nWriting output to " + options.outFile.getName()
        + "...");

        try (PrintStream ps = new PrintStream(options.outFile)) {
            ps.print(logReader.getPreamble());
            ps.println("tree STATE_0 = " + acgBest.getExtendedNewick());

            String postamble = logReader.getPostamble();
            if (postamble.length() > 0)
                ps.println(postamble);
            else
                ps.println("End;");
        }

        System.out.println("\nDone!");
    }

    /**
     * Annotate nodes of given clonal frame with summarized height information.
     *
     * @param cladeSystem information summarizing ACG posterior
     * @param root root of clonal frame to annotate
     * @param heightStrategy strategy used when summarizing CF node ages/heights
     */
    protected void annotateCF(ACGCladeSystem cladeSystem,
                              Node root, HeightStrategy heightStrategy) {

        cladeSystem.applyToClades(root, (node, bits) -> {
            List<Object[]> rawHeights =
                    cladeSystem.getCladeMap().get(bits).getAttributeValues();

            double cladeCredibility = cladeSystem.getCladeMap()
                    .get(bits).getCredibility();

            double[] heights = new double[rawHeights.size()];
            for (int i = 0; i < rawHeights.size(); i++)
                heights[i] = (double) rawHeights.get(i)[0];

            if (heightStrategy == HeightStrategy.MEAN)
                node.setHeight(DiscreteStatistics.mean(heights));
            else
                node.setHeight(DiscreteStatistics.median(heights));

            Arrays.sort(heights);
            double minHPD = heights[(int)(0.025 * heights.length)];
            double maxHPD = heights[(int)(0.975 * heights.length)];

            node.metaDataString = "posterior=" + cladeCredibility
                    + ", height_95%_HPD={" + minHPD + "," + maxHPD + "}";

            return null;
        });
    }

    /**
     * Add summarized conversions to given ACG.
     *
     * @param cladeSystem information summarizing ACG posterior
     * @param acg conversion graph
     * @param threshold significance threshold
     * @param heightStrategy strategy used when summarizing event ages/heights
     */
    protected void summarizeConversions(ACGCladeSystem cladeSystem,
                                        ConversionGraph acg,
                                        int nACGs,
                                        double threshold,
                                        HeightStrategy heightStrategy) {

        BitSet[] bitSets = cladeSystem.getBitSets(acg);
        for (int fromNr=0; fromNr<acg.getNodeCount(); fromNr++) {
            BitSet from = bitSets[fromNr];
            for (int toNr=0; toNr<acg.getNodeCount(); toNr++) {
                BitSet to = bitSets[toNr];

                for (Locus locus : acg.getLoci()) {
                    List<ACGCladeSystem.ConversionSummary> conversionSummaries =
                            cladeSystem.getConversionSummaries(from, to, locus,
                                    nACGs, threshold);

                    for (ACGCladeSystem.ConversionSummary conversionSummary
                            : conversionSummaries) {


                        Conversion conv = new Conversion();
                        conv.setLocus(locus);
                        conv.setNode1(acg.getNode(fromNr));
                        conv.setNode2(acg.getNode(toNr));
                        conv.setStartSite(conversionSummary.startSite);
                        conv.setEndSite(conversionSummary.endSite);

                        double posteriorSupport = conversionSummary.maxOverlaps/(double)nACGs;

                        double[] height1s = new double[conversionSummary.summarizedConvCount()];
                        double[] height2s = new double[conversionSummary.summarizedConvCount()];
                        for (int i=0; i<conversionSummary.summarizedConvCount(); i++) {
                            height1s[i] = conversionSummary.height1s.get(i);
                            height2s[i] = conversionSummary.height2s.get(i);
                        }

                        if (heightStrategy == HeightStrategy.MEAN) {
                            conv.setHeight1(DiscreteStatistics.mean(height1s));
                            conv.setHeight2(DiscreteStatistics.mean(height2s));
                        } else {
                            conv.setHeight1(DiscreteStatistics.median(height1s));
                            conv.setHeight2(DiscreteStatistics.median(height2s));
                        }

                        Arrays.sort(height1s);
                        double minHPD1 = height1s[(int)(0.025 * height1s.length)];
                        double maxHPD1 = height1s[(int)(0.975 * height1s.length)];

                        Arrays.sort(height2s);
                        double minHPD2 = height2s[(int)(0.025 * height2s.length)];
                        double maxHPD2 = height2s[(int)(0.975 * height2s.length)];

                        conv.newickMetaData1 = "posterior=" + posteriorSupport
                                + ", height_95%_HPD={" + minHPD1 + "," + maxHPD1 + "}";
                        conv.newickMetaData2 = "height_95%_HPD={" + minHPD2 + "," + maxHPD2 + "}";

                        acg.addConversion(conv);
                    }
                }

            }
        }
    }

    /**
     * Use a GUI to retrieve ACGAnnotator options.
     *
     * @param options options object to populate using GUI
     * @return true if options successfully collected, false otherwise
     */
    private static boolean getOptionsGUI(ACGAnnotatorOptions options) {

        boolean[] canceled = {false};

        JDialog dialog = new JDialog((JDialog)null, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLocationRelativeTo(null);
        dialog.setTitle("ACGAnnotator");

        JLabel logFileLabel = new JLabel("ACG log file:");
        JLabel outFileLabel = new JLabel("Output file:");
        JLabel burninLabel = new JLabel("Burn-in percentage:");
        JLabel heightMethodLabel = new JLabel("Node height method:");
        JLabel thresholdLabel = new JLabel("Posterior conversion support threshold:");

        JTextField inFilename = new JTextField(20);
        inFilename.setEditable(false);
        JButton inFileButton = new JButton("Choose File");

        JTextField outFilename = new JTextField(20);
        outFilename.setText(options.outFile.getName());
        outFilename.setEditable(false);
        JButton outFileButton = new JButton("Choose File");

        JSlider burninSlider = new JSlider(JSlider.HORIZONTAL,
                0, 100, 10);
        burninSlider.setMajorTickSpacing(50);
        burninSlider.setMinorTickSpacing(10);
        burninSlider.setPaintTicks(true);
        burninSlider.setPaintLabels(true);

        JComboBox<HeightStrategy> heightMethodCombo = new JComboBox<>(HeightStrategy.values());

        JSlider thresholdSlider = new JSlider(JSlider.HORIZONTAL,
                0, 100, 95);
        thresholdSlider.setMajorTickSpacing(50);
        thresholdSlider.setMinorTickSpacing(10);
        thresholdSlider.setPaintTicks(true);
        thresholdSlider.setPaintLabels(true);

        Container cp = dialog.getContentPane();
        BoxLayout boxLayout = new BoxLayout(cp, BoxLayout.PAGE_AXIS);
        cp.setLayout(boxLayout);

        JPanel mainPanel = new JPanel();

        GroupLayout layout = new GroupLayout(mainPanel);
        mainPanel.setLayout(layout);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);

        layout.setHorizontalGroup(layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup()
                        .addComponent(logFileLabel)
                        .addComponent(outFileLabel)
                        .addComponent(burninLabel)
                        .addComponent(heightMethodLabel)
                        .addComponent(thresholdLabel))
                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING, false)
                        .addComponent(inFilename)
                        .addComponent(outFilename)
                        .addComponent(burninSlider)
                        .addComponent(heightMethodCombo)
                        .addComponent(thresholdSlider))
                .addGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING, false)
                        .addComponent(inFileButton)
                        .addComponent(outFileButton)));

        layout.setVerticalGroup(layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup()
                        .addComponent(logFileLabel)
                        .addComponent(inFilename,
                                GroupLayout.PREFERRED_SIZE,
                                GroupLayout.DEFAULT_SIZE,
                                GroupLayout.PREFERRED_SIZE)
                        .addComponent(inFileButton))
                .addGroup(layout.createParallelGroup()
                        .addComponent(outFileLabel)
                        .addComponent(outFilename,
                                GroupLayout.PREFERRED_SIZE,
                                GroupLayout.DEFAULT_SIZE,
                                GroupLayout.PREFERRED_SIZE)
                        .addComponent(outFileButton))
                .addGroup(layout.createParallelGroup()
                        .addComponent(burninLabel)
                        .addComponent(burninSlider,
                                GroupLayout.PREFERRED_SIZE,
                                GroupLayout.DEFAULT_SIZE,
                                GroupLayout.PREFERRED_SIZE))
                .addGroup(layout.createParallelGroup()
                        .addComponent(heightMethodLabel)
                        .addComponent(heightMethodCombo,
                                GroupLayout.PREFERRED_SIZE,
                                GroupLayout.DEFAULT_SIZE,
                                GroupLayout.PREFERRED_SIZE))
                .addGroup(layout.createParallelGroup()
                        .addComponent(thresholdLabel)
                        .addComponent(thresholdSlider,
                                GroupLayout.PREFERRED_SIZE,
                                GroupLayout.DEFAULT_SIZE,
                                GroupLayout.PREFERRED_SIZE)));

        mainPanel.setBorder(new EtchedBorder());
        cp.add(mainPanel);

        JPanel buttonPanel = new JPanel();

        JButton runButton = new JButton("Analyze");
        runButton.addActionListener((e) -> {
            options.burninPercentage = burninSlider.getValue();
            options.convPosteriorThresholdPercentage = thresholdSlider.getValue();
            options.heightStrategy = (HeightStrategy)heightMethodCombo.getSelectedItem();
            dialog.setVisible(false);
        });
        runButton.setEnabled(false);
        buttonPanel.add(runButton);

        JButton cancelButton = new JButton("Quit");
        cancelButton.addActionListener((e) -> {
            dialog.setVisible(false);
            canceled[0] = true;
        });
        buttonPanel.add(cancelButton);

        JFileChooser inFileChooser = new JFileChooser();
        inFileButton.addActionListener(e -> {
            inFileChooser.setDialogTitle("Select ACG log file to summarize");
            if (options.inFile == null)
                inFileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
            int returnVal = inFileChooser.showOpenDialog(dialog);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                options.inFile = inFileChooser.getSelectedFile();
                inFilename.setText(inFileChooser.getSelectedFile().getName());
                runButton.setEnabled(true);
            }
        });

        JFileChooser outFileChooser = new JFileChooser();
        outFileButton.addActionListener(e -> {
            outFileChooser.setDialogTitle("Select output file name.");
            if (options.inFile != null)
                outFileChooser.setCurrentDirectory(options.inFile);
            else
                outFileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));

            outFileChooser.setSelectedFile(options.outFile);
            int returnVal = outFileChooser.showOpenDialog(dialog);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                options.outFile = outFileChooser.getSelectedFile();
                outFilename.setText(outFileChooser.getSelectedFile().getName());
            }
        });

        cp.add(buttonPanel);

        dialog.pack();
        dialog.setResizable(false);
        dialog.setVisible(true);

        return !canceled[0];
    }

    /**
     * Prepare JFrame to which ACGAnnotator output streams will be
     * directed.
     */
    private static void setupGUIOutput() {
        JFrame frame = new JFrame();
        frame.setTitle("ACGAnnotator");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JTextArea textArea = new JTextArea(25, 80);
        textArea.setFont(new Font("monospaced", Font.PLAIN, 12));
        textArea.setEditable(false);
        frame.getContentPane().add(new JScrollPane(textArea), BorderLayout.CENTER);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> System.exit(0));
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(closeButton);
        frame.getContentPane().add(buttonPanel, BorderLayout.PAGE_END);

        // Redirect streams to output window:
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                SwingUtilities.invokeLater(() -> {
                    if ((char)b == '\r') {
                        int from = textArea.getText().lastIndexOf("\n") + 1;
                        int to = textArea.getText().length();
                        textArea.replaceRange(null, from, to);
                    } else
                        textArea.append(String.valueOf((char) b));
                });
            }
        };

        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));

        frame.pack();
        frame.setVisible(true);
    }

    public static String helpMessage =
            "ACGAnnotator - produces summaries of Bacter ACG log files.\n"
                    + "\n"
                    + "Usage: appstore ACGAnnotator [-help | [options] logFile [outputFile]\n"
                    + "\n"
                    + "Option                   Description\n"
                    + "--------------------------------------------------------------\n"
                    + "-help                    Display usage info.\n"
                    + "-heights {mean,median}   Choose node height method.\n"
                    + "-burnin percentage       Choose _percentage_ of log to discard\n"
                    + "                         in order to remove burn-in period.\n"
                    + "-threshold percentage    Choose minimum posterior probability\n" +
                    "                           for including conversion in summary.";

    /**
     * Print usage info and exit.
     */
    public static void printUsageAndExit() {
        System.out.println(helpMessage);
        System.exit(0);
    }

    /**
     * Display error, print usage and exit with error.
     */
    public static void printUsageAndError() {
        System.err.println("Error processing command line parameters.\n");
        System.err.println(helpMessage);
        System.exit(1);
    }

    /**
     * Retrieve ACGAnnotator options from command line.
     *
     * @param args command line arguments
     * @param options object to populate with options
     */
    public static void getCLIOptions(String[] args, ACGAnnotatorOptions options) {
        int i=0;
        while (args[i].startsWith("-")) {
            switch(args[i]) {
                case "-help":
                    printUsageAndExit();
                    break;

                case "-burnin":
                    if (args.length<=i+1)
                        printUsageAndError();

                    try {
                        options.burninPercentage = Double.parseDouble(args[i+1]);
                    } catch (NumberFormatException e) {
                        printUsageAndError();
                    }

                    if (options.burninPercentage<0 || options.burninPercentage>100)
                        printUsageAndError();

                    i += 1;
                    break;

                case "-heights":
                    if (args.length<=i+1)
                        printUsageAndError();

                    if (args[i+1].toLowerCase().equals("mean")) {
                        options.heightStrategy = HeightStrategy.MEAN;

                        i += 1;
                        break;
                    }

                    if (args[i+1].toLowerCase().equals("median")) {
                        options.heightStrategy = HeightStrategy.MEDIAN;

                        i += 1;
                        break;
                    }

                    printUsageAndError();

                case "-threshold":
                    if (args.length<=i+1)
                        printUsageAndError();

                    try {
                        options.convPosteriorThresholdPercentage =
                                Double.parseDouble(args[i + 1]);
                    } catch (NumberFormatException e) {
                        printUsageAndError();
                    }

                    i += 1;
                    break;

                default:
                    printUsageAndError();
            }

            i += 1;
        }

        if (i >= args.length)
            printUsageAndError();
        else
            options.inFile = new File(args[i]);

        if (i+1<args.length)
            options.outFile = new File(args[i+1]);
    }

    /**
     * Main method for ACGAnnotator.  Sets up GUI if needed then
     * uses the ACGAnnotator constructor to actually perform the analysis.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        ACGAnnotatorOptions options = new ACGAnnotatorOptions();

        if (args.length == 0) {
            // Retrieve options from GUI:
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (!getOptionsGUI(options))
                        System.exit(0);

                    setupGUIOutput();
                });
            } catch (InterruptedException | InvocationTargetException e) {
                e.printStackTrace();
            }


        } else {
            getCLIOptions(args, options);
        }

        // Run ACGAnnotator
        try {
            new ACGAnnotator(options);

        } catch (Exception e) {
            if (args.length == 0) {
                JOptionPane.showMessageDialog(null, e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
                System.err.println();
                System.err.println(helpMessage);
            }

            System.exit(1);
        }
    }
}
