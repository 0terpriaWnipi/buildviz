var tooltip = function () {
    var tooltip = d3.select("body").append("div")
            .attr("class", "tooltip")
            .style("display", "none");

    var pointerIsOnLeftSideOfScreen = function () {
        var windowRect = document.body.getBoundingClientRect();
        return d3.event.pageX < (windowRect.width / 2);
    };

    var mouseover = function (html) {
        var targetRect = d3.event.target.getBoundingClientRect(),
            windowRect = document.body.getBoundingClientRect();
        tooltip
            .style("display", "inline")
            .html(html)
            .style("top", (d3.event.pageY + 20) + "px");
        if (pointerIsOnLeftSideOfScreen()) {
            tooltip
                .style("left", (d3.event.pageX + 10) + "px")
                .style("right", "");
        } else {
            tooltip
                .style("left", "")
                .style("right", (windowRect.width - d3.event.pageX + 10) + "px");
        }
    };

    var mouseout = function () {
        tooltip.style("display", "none");
    };

    var register = function (element, htmlFactory) {
        element
            .on("mouseover", function (d) { mouseover(htmlFactory(d)); })
            .on("mouseout", mouseout);
    };


    return {
        register: register
    };
}();