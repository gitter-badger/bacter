/*
 * Copyright (C) 2013 Tim Vaughan <tgvaughan@gmail.com>
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
package argbeast;

import beast.evolution.tree.Node;

/**
 * A class representing recombination events that are one-edge
 * modifications to the clonal frame.
 * 
 * @author Tim Vaughan <tgvaughan@gmail.com>
 */
public class Recombination  {
    
    public Recombination() { }
    
    /**
     * Construct new recombination with specified properties.
     * @param node1
     * @param height1
     * @param node2
     * @param height2
     * @param startLocus
     * @param endLocus 
     */
    public Recombination(Node node1, double height1, Node node2, double height2,
            int startLocus, int endLocus) {
        this.node1 = node1;
        this.node2 = node2;
        this.height1 = height1;
        this.height2 = height2;
        this.startLocus = startLocus;
        this.endLocus = endLocus;
    }

    /**
     * Nodes below branches to which recombinant edge connects.
     */
    Node node1, node2;
    
    /**
     * Heights on branches at which recombinant edge connects.
     */
    double height1, height2;
    
    /**
     * Range of nucleotides affected by homologous gene conversion.
     */
    int startLocus, endLocus;
    
}
