const graphFactory = (function(d3) {
    "use strict";
    const module = {};

    module.size = 600;

    module.textWithLineBreaks = function(elem, lines) {
        const textElem = d3.select(elem),
            lineHeight = 1.1,
            yCorrection = (lineHeight * lines.length) / 2 - 0.95;

        lines.forEach(function(line, idx) {
            textElem
                .append("tspan")
                .attr("x", 0)
                .attr("y", lineHeight * idx - yCorrection + "em")
                .text(line);
        });
    };

    module.create = function(params) {
        const id = "graph_" + params.id,
            widget = d3
                .select(document.currentScript.parentNode)
                .append("section")
                .attr("class", "graph " + params.id)
                .attr("id", id);

        widget
            .append("a")
            .attr("class", "close")
            .attr("href", "#")
            .text("╳");

        const header = widget.append("header");
        header
            .append("a")
            .attr("class", "enlarge")
            .attr("href", "#" + id)
            .append("h1").text(params.headline);

        if (params.widgets) {
            params.widgets.reverse().forEach(function(widget) {
                header.node().appendChild(widget);
            });
        }

        widget.append("div").attr("class", "loader");

        const svg = widget
                .append("svg")
                .attr("preserveAspectRatio", "xMinYMin meet")
                .attr("viewBox", "0 0 " + module.size + " " + module.size),
            noDataExplanation = params.noDataReason
                ? "<p>Recent entries will appear once you've " +
                  params.noDataReason +
                  "</p>"
                : "";

        widget
            .append("p")
            .attr("class", "nodata")
            .html("No data" + noDataExplanation);

        return {
            svg: svg,
            loaded: function() {
                widget.classed("loading", false);
            },
            loading: function() {
                widget.classed("loading", true);
            }
        };
    };

    return module;
})(d3);
