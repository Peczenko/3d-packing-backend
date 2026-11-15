package com.packing.backend.infra.packing.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Packer names every object of a multi-container result "bin_<container>_mesh_<input>", both one-based, in input order.
final class BinMembership {

    private static final Pattern NAME = Pattern.compile("bin_(\\d{1,9})_mesh_(\\d{1,9})");

    private final List<InstanceExtractor.Item> items;
    private final int[][]                      copies;

    private int objects;
    private int itemIndex;
    private int seenOfItem;

    BinMembership(List<InstanceExtractor.Item> items, int binCount) {
        if (binCount < 2) {
            throw new IllegalArgumentException("Only a result in more than one container has container membership");
        }
        this.items = List.copyOf(items);
        this.copies = new int[binCount][items.size()];
    }

    PackerObjReader.Sink observing(PackerObjReader.Sink sink) {
        return new PackerObjReader.Sink() {

            @Override
            public void object(String name) {
                record(name);
                sink.object(name);
            }

            @Override
            public void vertex(double x, double y, double z) {
                sink.vertex(x, y, z);
            }

            @Override
            public void triangle(int a, int b, int c) {
                sink.triangle(a, b, c);
            }
        };
    }

    List<InstanceExtractor.Item> layout(int number) {
        List<InstanceExtractor.Item> layout = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int count = copies[number - 1][i];
            if (count > 0) {
                layout.add(new InstanceExtractor.Item(items.get(i)
                                                           .name(),
                                                      count));
            }
        }
        if (layout.isEmpty()) {
            throw InstanceExtractor.mismatch("container " + number + " holds no objects");
        }
        return layout;
    }

    private void record(String name) {
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches()) {
            throw InstanceExtractor.mismatch("object '" + name + "' does not name its container");
        }
        int bin = Integer.parseInt(matcher.group(1));
        int mesh = Integer.parseInt(matcher.group(2));
        if (mesh != objects + 1) {
            throw InstanceExtractor.mismatch("object '" + name + "' is not object " + (objects + 1) + " in input order");
        }
        if (bin < 1 || bin > copies.length) {
            throw InstanceExtractor.mismatch("object '" + name + "' is in a container the job did not fill");
        }
        if (seenOfItem == items.get(itemIndex)
                               .copies()) {
            itemIndex++;
            seenOfItem = 0;
            if (itemIndex >= items.size()) {
                throw InstanceExtractor.mismatch("it holds more objects than the job placed");
            }
        }
        copies[bin - 1][itemIndex]++;
        seenOfItem++;
        objects++;
    }
}
